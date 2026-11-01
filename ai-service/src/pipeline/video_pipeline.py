import time
import logging
import numpy as np
from typing import List, Optional, Tuple, Any

from ..config import settings
from ..core.entity import Point, TrackedPerson, SecurityAlertEvent, RoiPolygonConfig, BoundingBox, MotionResult
from ..core.motion_detector import MotionDetector
from ..core.zone_state_machine import ZoneStateMachine, ZoneState
from ..core.human_detector import HumanDetector
from ..core.face_detector import FaceDetector
from ..core.face_matcher import FaceMatcher
from ..core.frame_analysis_engine import FrameAnalysisEngine
from ..integration.mqtt_client import SecurityMqttClient, edge_mqtt_client
from ..integration.storage_service import StorageService
from ..utils.visualizer import FrameVisualizer
from ..utils.geometry import is_point_in_polygon, scale_polygon_to_frame
from .incident_recorder import incident_recording_manager

logger = logging.getLogger(__name__)

class VideoPipeline:
    """
    Pipeline tích hợp toàn diện quy trình phân tích luồng Video:
    1. Tiền xử lý chuyển động & Chống phá hoại (OpenCV MOG2 MotionDetector)
    2. Cổng lọc tải & Máy trạng thái chốt giữ (ZoneStateMachine Latch)
    3. Human Detection & Tracking (YOLO26 + ByteTrack)
    4. Đối soát ngụy trang / Xâm nhập bất thường (SUSPICIOUS_MOTION)
    5. Crop vùng người & Face Detection (YuNet)
    6. Face Recognition Matching (pgvector Cosine Search)
    7. Violation Analysis (ROI Polygon Check)
    8. Cảnh báo tự động (MTQQ Event + MinIO Snapshot Upload)
    9. Visualization & Overlay Rendering
    """
    def __init__(
        self,
        camera_code: str,
        roi_polygon: Optional[List[Point]] = None,
        conf_threshold: Optional[float] = None,
        model_yolo_path: Optional[str] = None,
        model_yunet_path: Optional[str] = None,
        roi_polygons: Optional[List[Any]] = None,
        after_hour_start: Optional[str] = None,
        after_hour_end: Optional[str] = None,
        enable_motion_gating: bool = True
    ):
        self.camera_code = camera_code
        self.roi_polygon = roi_polygon or []
        self.roi_polygons: List[RoiPolygonConfig] = []

        if roi_polygons:
            self.set_roi_config(roi_polygons)
        elif self.roi_polygon:
            self.set_roi_polygon(self.roi_polygon)

        # Khởi tạo các module core
        yolo_path = model_yolo_path or settings.MODEL_YOLO_PATH
        yunet_path = model_yunet_path or settings.MODEL_YUNET_PATH
        detector_conf = conf_threshold if conf_threshold is not None else settings.DEFAULT_DETECTION_CONFIDENCE
        ah_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        ah_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END

        logger.info(f"Khởi tạo VideoPipeline cho Camera [{camera_code}]...")
        self.human_detector = HumanDetector(model_path=yolo_path, conf_threshold=detector_conf)
        self.face_detector = FaceDetector(model_path=yunet_path, score_threshold=settings.DEFAULT_FACE_CONFIDENCE)
        self.face_matcher = FaceMatcher()
        self.analysis_engine = FrameAnalysisEngine(after_hour_start=ah_start, after_hour_end=ah_end)

        # Khởi tạo module Motion Detector & State Machine
        self.enable_motion_gating = enable_motion_gating
        self.motion_detector = MotionDetector(
            history=500,
            var_threshold=25.0,
            min_motion_area=getattr(settings, "MIN_MOTION_AREA", 3000),
            lighting_spike_ratio=0.70,
            tampering_pixel_val=245,
            tampering_area_ratio=0.05,
            tampering_duration_threshold=1.5
        )
        self.state_machine = ZoneStateMachine(
            cooldown_seconds=5.0,
            motion_to_occupied_seconds=1.0
        )
        self._last_suspicious_motion_time: float = 0.0
        self._suspicious_motion_cooldown: float = 5.0
        self._last_tampering_time: float = 0.0
        self._tampering_cooldown: float = 10.0
        self._pending_access_checks = set()

        # Khởi tạo các module tích hợp
        from concurrent.futures import ThreadPoolExecutor
        self.executor = ThreadPoolExecutor(max_workers=2)
        self.mqtt_client = edge_mqtt_client
        self.kafka_producer = self.mqtt_client  # Backward compatibility
        self.storage_service = StorageService()
        self.visualizer = FrameVisualizer()

    def _apply_access_decision(self, face_info, is_allowed: bool, access_reason: str, area_name: Optional[str]):
        reason_lower = (access_reason or "").lower()
        if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
            face_info.matched_code = None
            face_info.matched_name = None
            face_info.is_authorized = False
            face_info.access_reason = "Đối tượng không xác định"
        else:
            face_info.is_authorized = is_allowed
            face_info.access_reason = access_reason
            face_info.area_name = area_name
        face_info.access_checked = True

    def _async_resolve_camera_access(self, track_id: int, user_code: str, roi_label: str):
        try:
            is_allowed, access_reason, area_name = self.face_matcher.check_camera_access(
                self.camera_code,
                user_code,
                roi_label=roi_label,
                event_direction=None
            )
            existing = self.analysis_engine.active_tracks.get(track_id)
            if existing and existing.face_info and existing.face_info.matched_code == user_code:
                self._apply_access_decision(existing.face_info, is_allowed, access_reason, area_name)
                existing.roi_entry_logged = True
        except Exception as e:
            logger.warning(f"[VideoPipeline] Lỗi kiểm tra quyền bất đồng bộ cho user {user_code}: {e}")
        finally:
            self._pending_access_checks.discard(f"{self.camera_code}:{user_code}")

    def _resolve_access_for_person(self, track_id: int, face_info, user_code: str, roi_label: str):
        if not self.face_matcher:
            face_info.is_authorized = True
            face_info.access_checked = True
            return

        cached = self.face_matcher.get_cached_access(self.camera_code, user_code)
        if cached is not None:
            is_allowed, access_reason, area_name = cached
            self._apply_access_decision(face_info, is_allowed, access_reason, area_name)
            return

        cache_key = f"{self.camera_code}:{user_code}"
        if cache_key not in self._pending_access_checks:
            self._pending_access_checks.add(cache_key)
            self.executor.submit(self._async_resolve_camera_access, track_id, user_code, roi_label)
        face_info.access_checked = False

    def set_roi_polygon(self, polygon: List[Point]):
        """Cập nhật tọa độ vùng cấm ROI (đơn lẻ, tương thích ngược)"""
        self.roi_polygon = polygon
        self.roi_polygons = [
            RoiPolygonConfig(
                label="Vùng ROI",
                alert_rules=["ENTRY_EXIT_TRACKING"],
                vertices=polygon
            )
        ]

    def set_roi_config(self, polygons: Optional[List[Any]] = None):
        """Cập nhật danh sách đa polygon ROI đầy đủ thuộc tính chuẩn hóa"""
        configs: List[RoiPolygonConfig] = []
        if polygons:
            for p in polygons:
                if isinstance(p, RoiPolygonConfig):
                    configs.append(p)
                elif isinstance(p, dict):
                    raw_vertices = p.get("vertices", [])
                    pts = [Point(v["x"], v["y"]) if isinstance(v, dict) else v for v in raw_vertices]
                    configs.append(
                        RoiPolygonConfig(
                            label=p.get("label", ""),
                            alert_rules=p.get("alert_rules", ["ENTRY_EXIT_TRACKING"]),
                            target_area_id=p.get("target_area_id"),
                            vertices=pts
                        )
                    )
        self.roi_polygons = configs
        if configs:
            self.roi_polygon = configs[0].vertices
        else:
            self.roi_polygon = []

    @staticmethod
    def _is_box_overlapping_any(
        box: BoundingBox,
        targets: List[BoundingBox],
        overlap_threshold: float = 0.15
    ) -> bool:
        """
        Kiểm tra xem 1 box (ví dụ motion box) có nằm trong hoặc trùng lặp đáng kể với bất kỳ target box (human box) nào không.
        """
        box_area = max(1.0, (box.x2 - box.x1) * (box.y2 - box.y1))
        cx = (box.x1 + box.x2) / 2.0
        cy = (box.y1 + box.y2) / 2.0

        for tgt in targets:
            # 1. Nếu tâm của motion box nằm trong human box (mở rộng dung sai 5px)
            if (tgt.x1 - 5 <= cx <= tgt.x2 + 5) and (tgt.y1 - 5 <= cy <= tgt.y2 + 5):
                return True

            # 2. Hoặc diện tích phần giao cắt chiếm >= overlap_threshold diện tích của motion box
            ix1 = max(box.x1, tgt.x1)
            iy1 = max(box.y1, tgt.y1)
            ix2 = min(box.x2, tgt.x2)
            iy2 = min(box.y2, tgt.y2)
            inter_w = max(0.0, ix2 - ix1)
            inter_h = max(0.0, iy2 - iy1)
            inter_area = inter_w * inter_h

            if (inter_area / box_area) >= overlap_threshold:
                return True

        return False

    def is_bbox_in_roi(self, bbox: BoundingBox, frame_w: int, frame_h: int) -> Tuple[bool, str]:
        """
        Kiểm tra xem vị trí chân người (bottom_center) có nằm trong bất kỳ polygon ROI nào không.
        Trả về: (in_roi: bool, matched_label: str)
        """
        if not self.roi_polygons and not self.roi_polygon:
            return True, "Toàn khung hình"

        bc = bbox.bottom_center
        if self.roi_polygons:
            for poly in self.roi_polygons:
                pts = poly.to_pixel_points(frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else poly.vertices
                if len(pts) >= 3 and is_point_in_polygon(bc, pts):
                    return True, poly.label or "Vùng ROI"
        elif self.roi_polygon and len(self.roi_polygon) >= 3:
            pts = scale_polygon_to_frame(self.roi_polygon, frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else self.roi_polygon
            if is_point_in_polygon(bc, pts):
                return True, "Vùng ROI"

        return False, ""

    def process_frame(
        self,
        frame: np.ndarray,
        current_time: Optional[float] = None
    ) -> Tuple[np.ndarray, List[TrackedPerson], List[SecurityAlertEvent]]:
        """
        Xử lý 1 khung hình qua toàn bộ pipeline.
        Trả về: (annotated_frame, danh sách người đang được track, danh sách cảnh báo phát sinh)
        """
        if frame is None or frame.size == 0:
            return frame, [], []

        if current_time is None:
            current_time = time.time()

        early_alerts: List[SecurityAlertEvent] = []
        h, w = frame.shape[:2]

        # ------------------------------------------------------------------
        # 1. TIỀN XỬ LÝ CHUYỂN ĐỘNG BẰNG OPENCV MOTION DETECTOR (~1ms)
        # ------------------------------------------------------------------
        motion_res = self.motion_detector.detect(
            frame,
            self.roi_polygons if self.roi_polygons else None
        )

        # 1a. Kiểm tra hành vi phá hoại chiếu đèn (CAMERA_TAMPERING)
        if motion_res.is_tampering:
            if (current_time - self._last_tampering_time) >= self._tampering_cooldown:
                self._last_tampering_time = current_time
                tamper_alert = SecurityAlertEvent(
                    camera_code=self.camera_code,
                    event_type="CAMERA_TAMPERING",
                    details="Phát hiện hành vi chiếu đèn laser hoặc làm mù cảm biến camera",
                    confidence=1.0,
                    track_id=-1
                )
                early_alerts.append(tamper_alert)

        # 1b. Cổng Motion-Gating: Nếu ở IDLE và hoàn toàn không có chuyển động / chớp sáng
        #     Bỏ qua 100% suy luận Deep Learning nặng (YOLO26, YuNet, ArcFace) để giải phóng CPU/GPU
        if (
            self.enable_motion_gating
            and self.state_machine.state == ZoneState.IDLE
            and not motion_res.has_motion
            and not motion_res.is_lighting_spike
        ):
            annotated_frame = frame.copy()
            annotated_frame = FrameVisualizer.draw_all_rois(
                annotated_frame,
                polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
            )
            if early_alerts:
                for alert in early_alerts:
                    self.executor.submit(self._dispatch_alert_async, alert, annotated_frame)
            return annotated_frame, [], early_alerts

        # ------------------------------------------------------------------
        # 2. ĐÁNH THỨC PHÁT HIỆN & THEO DÕI NGƯỜI (YOLO26 + BYTETRACK)
        # ------------------------------------------------------------------
        detected_tracks = self.human_detector.detect_and_track(frame, persist=True)

        # 2a. TRIỆT TIÊU MOTION DETECTION TRONG BOUNDING BOX NGƯỜI (HUMAN MASKING)
        # Loại bỏ 100% các bounding box vàng của motion detection nằm bên trong hoặc trùng lên người.
        # Bao gồm cả người vừa phát hiện trong frame và người đang được duy trì track (active tracks).
        # Khi người cúi xuống / ngồi xuống khiến YOLO tạm thời trượt detection, chuyển động của họ
        # vẫn được nhận diện là của người đó -> duy trì track ID và KHÔNG cảnh báo ngụy trang giả!
        current_frame_tids = {tid for tid, _ in detected_tracks if tid >= 0}
        detected_boxes = [bbox for _, bbox in detected_tracks]
        active_tracked_boxes: List[BoundingBox] = []

        for tid, p in list(self.analysis_engine.active_tracks.items()):
            inactive_dur = current_time - p.last_seen_time
            if inactive_dur <= self.analysis_engine.max_inactive_seconds:
                # Kiểm tra xem vị trí của track cũ có đang trùng lấn với detection mới nào từ YOLO không
                has_active_yolo_det = any(
                    p.bbox.iou(d_box) > 0.25 or p.bbox.intersection_ratio(d_box) > 0.45
                    for d_box in detected_boxes
                )
                if has_active_yolo_det and tid not in current_frame_tids:
                    # Đã có detection mới của YOLO tại vị trí này -> Track cũ không còn visible, nhường quyền cho track mới
                    p.is_currently_visible = False
                elif tid not in current_frame_tids and motion_res.motion_boxes:
                    # Người thật sự cúi người/ngồi khuất và YOLO trượt detection:
                    overlapping_mb = [
                        mb for mb in motion_res.motion_boxes
                        if self._is_box_overlapping_any(mb, [p.bbox], overlap_threshold=0.08)
                    ]
                    if overlapping_mb:
                        # Duy trì sự tồn tại của track nhưng KHÔNG phình to box vô tận
                        p.last_seen_time = current_time
                        p.is_currently_visible = True
                active_tracked_boxes.append(p.bbox)

        all_human_boxes = [bbox for _, bbox in detected_tracks] + active_tracked_boxes
        if all_human_boxes and motion_res.motion_boxes:
            non_human_motion_boxes = [
                mb for mb in motion_res.motion_boxes
                if not self._is_box_overlapping_any(mb, all_human_boxes, overlap_threshold=0.08)
            ]
            motion_res.motion_boxes = non_human_motion_boxes
            if not motion_res.motion_boxes:
                motion_res.motion_area = 0

        # 2b. Kiểm tra người trong ROI và cập nhật Máy trạng thái chốt giữ
        person_in_roi = False
        for track_id, bbox in detected_tracks:
            in_roi, _ = self.is_bbox_in_roi(bbox, w, h)
            if in_roi:
                person_in_roi = True
                break

        # Nếu frame hiện tại YOLO trượt phát hiện nhưng người đang được track đang ở trong ROI
        if not person_in_roi:
            for p in self.analysis_engine.active_tracks.values():
                if (current_time - p.last_seen_time) <= self.analysis_engine.max_inactive_seconds:
                    in_roi, _ = self.is_bbox_in_roi(p.bbox, w, h)
                    if in_roi:
                        person_in_roi = True
                        break

        self.state_machine.update(
            has_motion=motion_res.has_motion,
            person_in_roi=person_in_roi,
            current_time=current_time
        )

        # 2c. Phát hiện Đột nhập Ngụy trang (SUSPICIOUS_MOVING_OBJECT):
        # Có chuyển động đáng kể trong ROI nhưng YOLO không nhận diện được dáng người (và không phải do người tạo ra)
        if motion_res.has_motion and not person_in_roi and len(motion_res.motion_boxes) > 0:
            if (current_time - self._last_suspicious_motion_time) >= self._suspicious_motion_cooldown:
                self._last_suspicious_motion_time = current_time
                cam_area_name = self.face_matcher.get_camera_area_name(self.camera_code) if self.face_matcher else None
                loc_name = cam_area_name or "Khu vực giám sát"

                anomaly_alert = SecurityAlertEvent(
                    camera_code=self.camera_code,
                    event_type="SUSPICIOUS_MOVING_OBJECT",
                    details=f"[SUSPICIOUS_MOVING_OBJECT] Vị trí: {loc_name}",
                    confidence=0.9,
                    track_id=-1
                )
                early_alerts.append(anomaly_alert)

        # ------------------------------------------------------------------
        # 3. QUÉT KHUÔN MẶT TRÊN TỪNG NGƯỜI (YUNET + ARCFACE)
        # ------------------------------------------------------------------
        for track_id, bbox in detected_tracks:
            if track_id < 0:
                continue

            in_roi, roi_label = self.is_bbox_in_roi(bbox, w, h)

            # Tối ưu hiệu năng: Nếu Track ID này đã nhận diện được danh tính trong active_tracks,
            # ByteTrack tiếp tục bám theo mượt mà mà không cần chạy lại YuNet + ArcFace + pgvector mỗi frame!
            existing = self.analysis_engine.active_tracks.get(track_id)
            if existing and existing.face_detected and existing.face_info and existing.face_info.matched_code:
                # Nếu người này trước đó ở ngoài ROI, nay bước chân vào ROI:
                if in_roi and not getattr(existing.face_info, "access_checked", False):
                    self._resolve_access_for_person(
                        track_id,
                        existing.face_info,
                        existing.face_info.matched_code,
                        roi_label or "Vùng giám sát"
                    )
                continue

            x1, y1, x2, y2 = bbox.to_int_xyxy()
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w, x2), min(h, y2)

            if (x2 - x1) > 20 and (y2 - y1) > 20:
                crop_h = int((y2 - y1) * settings.UPPER_BODY_CROP_RATIO)
                person_upper_crop = frame[y1:y1 + crop_h, x1:x2]
                
                if person_upper_crop.size > 0:
                    face_result = self.face_detector.detect_best_face_in_crop(
                        person_upper_crop,
                        offset_xy=(x1, y1)
                    )
                    if face_result:
                        fx1, fy1, fx2, fy2 = face_result.bbox.to_int_xyxy()
                        fx1, fy1 = max(0, fx1), max(0, fy1)
                        fx2, fy2 = min(w, fx2), min(h, fy2)
                        face_crop = frame[fy1:fy2, fx1:fx2]
                        if face_crop.size > 0:
                            crop_landmarks = None
                            if face_result.landmarks:
                                crop_landmarks = [(pt[0] - fx1, pt[1] - fy1) for pt in face_result.landmarks]
                            match = self.face_matcher.match_face(
                                face_crop,
                                threshold=settings.FACE_MATCH_THRESHOLD,
                                landmarks=crop_landmarks
                            )
                            if match:
                                if len(match) >= 5:
                                    code, name, score, is_active, role = match
                                else:
                                    code, name, score, is_active = match[:4]
                                    role = "NORMAL_USER"
                                face_result.matched_code = code
                                face_result.matched_name = name
                                face_result.is_active = is_active
                                face_result.role = role

                                # CHỈ kiểm tra thẩm quyền và ghi nhận truy cập khi đã thực sự bước chân vào vùng ROI!
                                if in_roi:
                                    self._resolve_access_for_person(
                                        track_id,
                                        face_result,
                                        code,
                                        roi_label or "Vùng giám sát"
                                    )
                                else:
                                    # Người dùng đang ở ngoài vùng ROI: Trạng thái bình thường, không ghi log vi phạm
                                    face_result.is_authorized = True
                                    face_result.access_reason = "Đang ở ngoài vùng ROI"
                                    face_result.access_checked = False

                        self.analysis_engine.associate_face(track_id, face_result)

        # ------------------------------------------------------------------
        # 4. PHÂN TÍCH XÂM NHẬP VÙNG CẤM (FRAME ANALYSIS ENGINE)
        # ------------------------------------------------------------------
        is_public = self.face_matcher.is_camera_public(self.camera_code) if self.face_matcher else False
        cam_area_name = self.face_matcher.get_camera_area_name(self.camera_code) if self.face_matcher else None
        active_persons, alerts, roi_events = self.analysis_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygon=self.roi_polygon,
            camera_code=self.camera_code,
            current_time=current_time,
            roi_polygons=self.roi_polygons,
            frame_size=(w, h),
            is_public_area=is_public,
            area_name=cam_area_name
        )

        if early_alerts:
            alerts = early_alerts + alerts

        # 4b. Xử lý ghi log sự kiện RA / VÀO vùng ROI ngầm (Async qua ThreadPoolExecutor)
        if roi_events and self.face_matcher:
            for ev in roi_events:
                self.executor.submit(
                    self.face_matcher.check_camera_access,
                    self.camera_code,
                    ev["user_code"],
                    ev["roi_label"],
                    ev["direction"],
                    ev.get("duration"),
                    True
                )

        has_roi = bool(self.roi_polygons or self.roi_polygon)

        # ------------------------------------------------------------------
        # 5. XỬ LÝ LƯU BẰNG CHỨNG & GỬI CẢNH BÁO TỰ ĐỘNG (ASYNC KAFKA / MINIO)
        # ------------------------------------------------------------------
        if alerts:
            for alert in alerts:
                ev_type = str(alert.event_type or "").upper()
                if any(k in ev_type for k in ["UNAUTHORIZED", "INTRUSION", "AFTER_HOUR", "OUT_OF_TIME", "UNKNOWN", "SUSPICIOUS_MOTION", "SUSPICIOUS_MOVING_OBJECT", "TAMPERING"]):
                    try:
                        incident_recording_manager.start_incident_recording(
                            incident_id=alert.event_id or f"EVT-{int(time.time()*1000)}",
                            camera_code=self.camera_code,
                            event_type=alert.event_type,
                            details=alert.details or "Phát hiện sự cố an ninh",
                        )
                    except Exception as e:
                        logger.error(f"Lỗi kích hoạt ghi hình sự cố cho camera [{self.camera_code}]: {e}")

                snapshot_frame = frame.copy()
                snapshot_frame = FrameVisualizer.draw_all_rois(
                    snapshot_frame,
                    polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
                )
                has_suspicious_motion_alert = any(a.event_type in ("SUSPICIOUS_MOVING_OBJECT", "SUSPICIOUS_MOTION") for a in alerts)
                if has_suspicious_motion_alert and motion_res.motion_boxes:
                    snapshot_frame = FrameVisualizer.draw_motion_boxes(snapshot_frame, motion_res.motion_boxes)

                snapshot_frame = self.visualizer.draw_tracked_persons(
                    snapshot_frame,
                    active_persons,
                    only_in_roi=has_roi
                )
                self.executor.submit(self._dispatch_alert_async, alert, snapshot_frame)

        # ------------------------------------------------------------------
        # 6. RENDER CÁC LỚP ĐỒ HỌA LÊN KHUNG HÌNH HIỂN THỊ (OVERLAY ANNOTATIONS)
        # ------------------------------------------------------------------
        annotated_frame = frame.copy()
        annotated_frame = FrameVisualizer.draw_all_rois(
            annotated_frame,
            polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
        )
        # Chỉ vẽ khung nghi vấn ngụy trang khi có cảnh báo SUSPICIOUS_MOTION thực sự trong ROI (đã lọc sạch cơ thể người)
        has_suspicious_motion_alert = any(a.event_type in ("SUSPICIOUS_MOVING_OBJECT", "SUSPICIOUS_MOTION") for a in alerts)
        if has_suspicious_motion_alert and motion_res.motion_boxes:
            annotated_frame = FrameVisualizer.draw_motion_boxes(annotated_frame, motion_res.motion_boxes)

        annotated_frame = self.visualizer.draw_tracked_persons(
            annotated_frame,
            active_persons,
            only_in_roi=getattr(settings, "DRAW_ONLY_IN_ROI", False) and has_roi
        )
        
        return annotated_frame, active_persons, alerts

    def _dispatch_alert_async(self, alert: SecurityAlertEvent, snapshot_frame: np.ndarray):
        try:
            evidence_url = self.storage_service.upload_frame_evidence(
                snapshot_frame,
                self.camera_code,
                alert.track_id,
                event_type=alert.event_type
            )
            alert.image_url = evidence_url
            self.mqtt_client.send_alert(alert)
        except Exception as e:
            logger.error(f"Lỗi gửi cảnh báo ngầm sang MQTT/MinIO: {e}")


