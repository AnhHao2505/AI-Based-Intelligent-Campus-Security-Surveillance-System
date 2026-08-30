import os
import cv2
import numpy as np
from typing import Optional, List, Tuple
from .entity import BoundingBox, FaceDetectionResult

class FaceDetector:
    """
    Module phát hiện khuôn mặt sử dụng mô hình ONNX YuNet của OpenCV.
    Hỗ trợ nhận diện khuôn mặt cả trên toàn khung hình và crop từ BBox của người.
    """
    def __init__(self, model_path: str, score_threshold: float = 0.6, nms_threshold: float = 0.3):
        self.model_path = model_path
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self.detector = None
        self._init_detector()

    def _init_detector(self):
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(f"Không tìm thấy mô hình YuNet tại: {self.model_path}")
        
        # Khởi tạo mặc định với kích thước tạm thời 320x320
        self.detector = cv2.FaceDetectorYN.create(
            model=self.model_path,
            config="",
            input_size=(320, 320),
            score_threshold=self.score_threshold,
            nms_threshold=self.nms_threshold,
            top_k=5000
        )

    def detect_in_image(self, image: np.ndarray, offset_xy: Tuple[int, int] = (0, 0)) -> List[FaceDetectionResult]:
        """
        Phát hiện các khuôn mặt trong ảnh/vùng crop.
        offset_xy: Tọa độ gốc (x_offset, y_offset) nếu truyền vào ảnh con đã crop để ánh xạ về frame gốc.
        """
        if image is None or image.size == 0:
            return []

        h, w, _ = image.shape
        if w < 10 or h < 10:
            return []

        self.detector.setInputSize((w, h))
        _, faces = self.detector.detect(image)

        results = []
        if faces is not None and len(faces) > 0:
            off_x, off_y = offset_xy
            for face in faces:
                bbox_raw = face[0:4].astype(float)
                score = float(face[-1])
                
                # Ánh xạ tọa độ khuôn mặt về frame gốc
                x1 = bbox_raw[0] + off_x
                y1 = bbox_raw[1] + off_y
                x2 = x1 + bbox_raw[2]
                y2 = y1 + bbox_raw[3]

                # Trích xuất 5 điểm mốc (landmarks: 2 mắt, mũi, 2 khóe miệng)
                landmarks = []
                raw_landmarks = face[4:14].astype(float).reshape((5, 2))
                for pt in raw_landmarks:
                    landmarks.append((pt[0] + off_x, pt[1] + off_y))

                results.append(
                    FaceDetectionResult(
                        bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2, confidence=score),
                        score=score,
                        landmarks=landmarks
                    )
                )

        return results

    def detect_best_face_in_crop(self, crop: np.ndarray, offset_xy: Tuple[int, int]) -> Optional[FaceDetectionResult]:
        """Tìm khuôn mặt có độ tin cậy cao nhất trong vùng crop của người"""
        faces = self.detect_in_image(crop, offset_xy=offset_xy)
        if not faces:
            return None
        # Sắp xếp theo score giảm dần
        faces.sort(key=lambda f: f.score, reverse=True)
        return faces[0]
