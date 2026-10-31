import time
import logging
import numpy as np
from typing import List, Optional, Tuple, Any

from ..config import settings
from ..core.entity import Point, TrackedPerson, SecurityAlertEvent, RoiPolygonConfig, BoundingBox
from ..core.human_detector import HumanDetector
from ..core.face_detector import FaceDetector
from ..core.face_matcher import FaceMatcher
from ..core.frame_analysis_engine import FrameAnalysisEngine
from ..integration.mqtt_client import SecurityMqttClient
from ..integration.storage_service import StorageService
from ..utils.visualizer import FrameVisualizer
from ..utils.geometry import is_point_in_polygon, scale_polygon_to_frame
from .incident_recorder import incident_recording_manager

logger = logging.getLogger(__name__)

class VideoPipeline:
    """
    Pipeline tích hợp toàn diện quy trình phân tích luồng Video:
    1. Human Detection & Tracking (YOLOv8 + ByteTrack)
    2. Crop vùng người & Face Detection (YuNet)
    3. Face Recognition Matching (pgvector Cosine Search)
    4. Violation Analysis (ROI Polygon Check)
    5. Cảnh báo tự động (MQTT Event + MinIO Snapshot Upload)
    6. Visualization & Overlay Rendering
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
        after_hour_end: Optional[str] = None
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

        # Khởi tạo các module tích hợp
        from concurrent.futures import ThreadPoolExecutor
        self.executor = ThreadPoolExecutor(max_workers=2)
        self.mqtt_client = SecurityMqttClient()
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

        # 2. Phát hiện & Theo dõi người (Human Detection + ByteTrack)
        detected_tracks = self.human_detector.detect_and_track(frame, persist=True)

        # 3. Quét khuôn mặt trên từng người được phát hiện (Face Detection & Recognition)
        h, w, _ = frame.shape
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

        # 4. Phân tích Xâm nhập vùng cấm
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

        # 5. Xử lý lưu bằng chứng và gửi cảnh báo tự động ngầm (Async không chặn luồng video)
        if alerts:
            for alert in alerts:
                # Kích hoạt ghi hình sự cố an ninh ngay lập tức khi phát hiện vi phạm
                ev_type = str(alert.event_type or "").upper()
                if any(k in ev_type for k in ["UNAUTHORIZED", "INTRUSION", "AFTER_HOUR", "UNKNOWN"]):
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
                snapshot_frame = self.visualizer.draw_tracked_persons(
                    snapshot_frame,
                    active_persons,
                    only_in_roi=has_roi
                )
                self.executor.submit(self._dispatch_alert_async, alert, snapshot_frame)

        # 6. Render các lớp đồ họa lên khung hình hiển thị (Overlay Annotations)
        annotated_frame = frame.copy()
        annotated_frame = FrameVisualizer.draw_all_rois(
            annotated_frame,
            polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
        )
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


