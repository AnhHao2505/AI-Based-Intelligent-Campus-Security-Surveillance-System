import time
import logging
import cv2
import numpy as np
from typing import List, Optional, Tuple, Dict, Any

from ..config import settings
from ..core.entity import Point, TrackedPerson, SecurityAlertEvent
from ..core.human_detector import HumanDetector
from ..core.face_detector import FaceDetector
from ..core.face_matcher import FaceMatcher
from ..core.loitering_engine import LoiteringEngine
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
    4. Loitering & Violation Analysis (ROI Polygon Check + Loitering Timer)
    5. Cảnh báo tự động (Kafka Event + MinIO Snapshot Upload)
    6. Visualization & Overlay Rendering
    """
    def __init__(
        self,
        camera_code: str = "CAM-001",
        roi_polygon: Optional[List[Point]] = None,
        loitering_threshold_seconds: int = 10,
        conf_threshold: float = 0.5,
        model_yolo_path: Optional[str] = None,
        model_yunet_path: Optional[str] = None
    ):
        self.camera_code = camera_code
        self.roi_polygon = roi_polygon or []
        self.loitering_threshold_seconds = loitering_threshold_seconds
        
        # Khởi tạo các module core
        yolo_path = model_yolo_path or settings.MODEL_YOLO_PATH
        yunet_path = model_yunet_path or settings.MODEL_YUNET_PATH
        
        logger.info(f"Khởi tạo VideoPipeline cho Camera [{camera_code}]...")
        self.human_detector = HumanDetector(model_path=yolo_path, conf_threshold=conf_threshold)
        self.face_detector = FaceDetector(model_path=yunet_path, score_threshold=settings.DEFAULT_FACE_CONFIDENCE)
        self.face_matcher = FaceMatcher()
        self.loitering_engine = LoiteringEngine(loitering_threshold_seconds=loitering_threshold_seconds)
        
        # Khởi tạo các module tích hợp
        self.kafka_producer = SecurityKafkaProducer()
        self.storage_service = StorageService()
        self.visualizer = FrameVisualizer()
        
        # Thống kê hiệu năng
        self.prev_frame_time = time.time()
        self.current_fps = 0.0

    def set_roi_polygon(self, polygon: List[Point]):
        """Cập nhật tọa độ vùng cấm ROI theo cấu hình camera"""
        self.roi_polygon = polygon

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

        # 1. Tính toán FPS thực tế
        dt = current_time - self.prev_frame_time
        self.current_fps = 1.0 / dt if dt > 0 else 30.0
        self.prev_frame_time = current_time

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
                crop_h = int((y2 - y1) * 0.6)
                person_upper_crop = frame[y1:y1 + crop_h, x1:x2]
                
                if person_upper_crop.size > 0:
                    face_result = self.face_detector.detect_best_face_in_crop(
                        person_upper_crop,
                        offset_xy=(x1, y1)
                    )
                    if face_result:
                        # Cắt khuôn mặt chính xác để so khớp đặc trưng với CSDL
                        fx1, fy1, fx2, fy2 = face_result.bbox.to_int_xyxy()
                        fx1, fy1 = max(0, fx1), max(0, fy1)
                        fx2, fy2 = min(w, fx2), min(h, fy2)
                        face_crop = frame[fy1:fy2, fx1:fx2]
                        if face_crop.size > 0:
                            match = self.face_matcher.match_face(face_crop, threshold=0.55)
                            if match:
                                code, name, score = match
                                face_result.matched_code = code
                                face_result.matched_name = name
                                face_result.is_authorized = True

                        self.loitering_engine.associate_face(track_id, face_result)

        # 4. Phân tích Loitering & Xâm nhập vùng cấm
        active_persons, alerts = self.loitering_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygon=self.roi_polygon,
            camera_code=self.camera_code,
            current_time=current_time
        )

        # 5. Xử lý lưu bằng chứng và gửi cảnh báo tự động
        for alert in alerts:
            # Tạo frame snapshot có vẽ thông tin cảnh báo
            snapshot_frame = frame.copy()
            snapshot_frame = self.visualizer.draw_roi(snapshot_frame, self.roi_polygon)
            snapshot_frame = self.visualizer.draw_tracked_persons(
                snapshot_frame,
                active_persons,
                self.loitering_threshold_seconds
            )
            
            # Upload ảnh chụp vi phạm lên MinIO
            evidence_url = self.storage_service.upload_frame_evidence(
                snapshot_frame,
                self.camera_code,
                alert.track_id
            )
            alert.image_url = evidence_url

            # Bắn sự kiện an ninh vào Kafka topic
            self.kafka_producer.send_alert(alert)

        # 6. Render các lớp đồ họa lên khung hình hiển thị (Overlay Annotations)
        annotated_frame = frame.copy()
        annotated_frame = self.visualizer.draw_roi(annotated_frame, self.roi_polygon)
        annotated_frame = self.visualizer.draw_tracked_persons(
            annotated_frame,
            active_persons,
            self.loitering_threshold_seconds
        )
        
        # Đếm số người đang lảng vảng trong ROI
        loiterers_count = sum(1 for p in active_persons if p.is_in_roi and p.loiter_duration >= self.loitering_threshold_seconds)
        annotated_frame = self.visualizer.draw_dashboard_overlay(
            annotated_frame,
            self.camera_code,
            self.current_fps,
            len(active_persons),
            loiterers_count
        )

        return annotated_frame, active_persons, alerts
