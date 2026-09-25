import time
import logging
import cv2
import numpy as np
from typing import List, Optional, Tuple, Dict, Any

from ..config import settings
from ..core.entity import Point, TrackedPerson, SecurityAlertEvent, RoiPolygonConfig
from ..core.human_detector import HumanDetector
from ..core.face_detector import FaceDetector
from ..core.face_matcher import FaceMatcher
from ..core.frame_analysis_engine import FrameAnalysisEngine
from ..integration.kafka_producer import SecurityKafkaProducer
from ..integration.storage_service import StorageService
from ..utils.visualizer import FrameVisualizer

logger = logging.getLogger(__name__)

class VideoPipeline:
    """
    Pipeline tích hợp toàn diện quy trình phân tích luồng Video:
    1. Human Detection & Tracking (YOLOv8 + ByteTrack)
    2. Crop vùng người & Face Detection (YuNet)
    3. Face Recognition Matching (pgvector Cosine Search)
    4. Violation Analysis (ROI Polygon Check)
    5. Cảnh báo tự động (Kafka Event + MinIO Snapshot Upload)
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
        self.kafka_producer = SecurityKafkaProducer()
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

    def set_roi_config(self, polygons: List[Any]):
        """Cập nhật danh sách đa polygon ROI đầy đủ thuộc tính chuẩn hóa"""
        configs: List[RoiPolygonConfig] = []
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
                            match = self.face_matcher.match_face(face_crop, threshold=settings.FACE_MATCH_THRESHOLD)
                            if match:
                                code, name, score = match
                                face_result.matched_code = code
                                face_result.matched_name = name
                                face_result.is_authorized = True

                        self.analysis_engine.associate_face(track_id, face_result)

        # 4. Phân tích Xâm nhập vùng cấm
        active_persons, alerts = self.analysis_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygon=self.roi_polygon,
            camera_code=self.camera_code,
            current_time=current_time,
            roi_polygons=self.roi_polygons,
            frame_size=(w, h)
        )

        # 5. Xử lý lưu bằng chứng và gửi cảnh báo tự động
        for alert in alerts:
            snapshot_frame = frame.copy()
            if self.roi_polygons:
                snapshot_frame = self.visualizer.draw_multiple_rois(snapshot_frame, self.roi_polygons)
            else:
                snapshot_frame = self.visualizer.draw_roi(snapshot_frame, self.roi_polygon)
            snapshot_frame = self.visualizer.draw_tracked_persons(
                snapshot_frame,
                active_persons
            )
            
            evidence_url = self.storage_service.upload_frame_evidence(
                snapshot_frame,
                self.camera_code,
                alert.track_id
            )
            alert.image_url = evidence_url

            self.kafka_producer.send_alert(alert)

        # 6. Render các lớp đồ họa lên khung hình hiển thị (Overlay Annotations)
        annotated_frame = frame.copy()
        if self.roi_polygons:
            annotated_frame = self.visualizer.draw_multiple_rois(annotated_frame, self.roi_polygons)
        else:
            annotated_frame = self.visualizer.draw_roi(annotated_frame, self.roi_polygon)
        annotated_frame = self.visualizer.draw_tracked_persons(
            annotated_frame,
            active_persons
        )
        
        return annotated_frame, active_persons, alerts
