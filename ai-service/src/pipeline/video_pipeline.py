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

        # Khởi tạo các module tích hợp
        from concurrent.futures import ThreadPoolExecutor
        self.executor = ThreadPoolExecutor(max_workers=2)
        self.mqtt_client = edge_mqtt_client
        self.kafka_producer = self.mqtt_client  # Backward compatibility
        self.storage_service = StorageService()
        self.visualizer = FrameVisualizer()

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

        # 2b. Kiểm tra người trong ROI và cập nhật Máy trạng thái chốt giữ
        person_in_roi = False
        for track_id, bbox in detected_tracks:
            in_roi, _ = self.is_bbox_in_roi(bbox, w, h)
            if in_roi:
                person_in_roi = True
                break

        self.state_machine.update(
            has_motion=motion_res.has_motion,
            person_in_roi=person_in_roi,
            current_time=current_time
        )

        # 2c. Phát hiện Đột nhập Ngụy trang (SUSPICIOUS_MOTION):
        # Có chuyển động đáng kể trong ROI nhưng YOLO26 không nhận diện được dáng người
        if motion_res.has_motion and not person_in_roi and len(motion_res.motion_boxes) > 0:
            if (current_time - self._last_suspicious_motion_time) >= self._suspicious_motion_cooldown:
                self._last_suspicious_motion_time = current_time
                anomaly_alert = SecurityAlertEvent(
                    camera_code=self.camera_code,
                    event_type="SUSPICIOUS_MOTION",
                    details=f"Phát hiện vật thể chuyển động bất thường trong vùng cấm không nhận diện được dáng người (Diện tích: {motion_res.motion_area}px, Nghi vấn ngụy trang/bò trườn)",
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
                    is_allowed, access_reason, area_name = self.face_matcher.check_camera_access(
                        self.camera_code,
                        existing.face_info.matched_code,
                        roi_label=roi_label or "Vùng giám sát",
                        event_direction="ENTRY"
                    )
                    reason_lower = (access_reason or "").lower()
                    if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
                        existing.face_info.matched_code = None
                        existing.face_info.matched_name = None
                        existing.face_info.is_authorized = False
                        existing.face_info.access_reason = "Đối tượng không xác định"
                    else:
                        existing.face_info.is_authorized = is_allowed
                        existing.face_info.access_reason = access_reason
                        existing.face_info.area_name = area_name
                    existing.face_info.access_checked = True
                    existing.roi_entry_logged = True
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
                                    is_allowed, access_reason, area_name = self.face_matcher.check_camera_access(
                                        self.camera_code,
                                        code,
                                        roi_label=roi_label or "Vùng giám sát",
                                        event_direction="ENTRY"
                                    )
                                    reason_lower = (access_reason or "").lower()
                                    if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
                                        face_result.matched_code = None
                                        face_result.matched_name = None
                                        face_result.is_authorized = False
                                        face_result.access_reason = "Đối tượng không xác định"
                                    else:
                                        face_result.is_authorized = is_allowed
                                        face_result.access_reason = access_reason
                                        face_result.area_name = area_name
                                    face_result.access_checked = True
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
        active_persons, alerts, roi_events = self.analysis_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygon=self.roi_polygon,
            camera_code=self.camera_code,
            current_time=current_time,
            roi_polygons=self.roi_polygons,
            frame_size=(w, h),
            is_public_area=is_public
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
                if any(k in ev_type for k in ["UNAUTHORIZED", "INTRUSION", "AFTER_HOUR", "OUT_OF_TIME", "UNKNOWN", "SUSPICIOUS_MOTION", "TAMPERING"]):
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
                if motion_res.has_motion and not person_in_roi:
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
        if motion_res.has_motion and not person_in_roi:
            annotated_frame = FrameVisualizer.draw_motion_boxes(annotated_frame, motion_res.motion_boxes)

        annotated_frame = self.visualizer.draw_tracked_persons(
            annotated_frame,
            active_persons,
            only_in_roi=False
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


