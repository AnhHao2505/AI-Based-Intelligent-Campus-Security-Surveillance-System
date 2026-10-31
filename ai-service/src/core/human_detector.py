import logging
import torch
from typing import List, Tuple, Optional
import numpy as np
from ultralytics import YOLO
from .entity import BoundingBox

logger = logging.getLogger(__name__)

class HumanDetector:
    """
    Module phát hiện & theo dõi con người sử dụng YOLO26 và thuật toán ByteTrack.
    Chỉ kích hoạt class 0 (Person) nhằm tối đa hóa hiệu năng và tốc độ xử lý Realtime.
    Tự động kích hoạt GPU CUDA (card rời) nếu có sẵn để giải phóng CPU.
    """
    def __init__(self, model_path: str = "yolo26n.pt", conf_threshold: float = 0.5, device: Optional[str] = None):
        self.model_path = model_path
        self.conf_threshold = conf_threshold
        
        # Tự động phát hiện GPU rời (CUDA) hoặc fallback về CPU
        if device is not None and device != "auto":
            self.device = device
        else:
            if torch.cuda.is_available():
                self.device = 0
                gpu_name = torch.cuda.get_device_name(0)
                logger.info(f"🚀 [HumanDetector] Phát hiện GPU rời: [{gpu_name}]. Kích hoạt suy luận YOLO26 trên CUDA.")
            else:
                self.device = "cpu"
                logger.info("ℹ️ [HumanDetector] Không phát hiện CUDA GPU, sử dụng CPU.")

        self.model = YOLO(model_path)

    def detect_and_track(self, frame: np.ndarray, persist: bool = True) -> List[Tuple[int, BoundingBox]]:
        """
        Thực hiện phát hiện người và gán Track ID liên tục qua các frames.
        Trả về danh sách tuple: [(track_id, BoundingBox), ...]
        """
        if frame is None or frame.size == 0:
            return []

        # Chạy YOLO26 tracking chỉ với class=0 (person)
        # tracker="bytetrack.yaml" được tích hợp sẵn trong Ultralytics
        results = self.model.track(
            source=frame,
            persist=persist,
            classes=[0],
            conf=self.conf_threshold,
            tracker="bytetrack.yaml",
            device=self.device,
            verbose=False
        )

        detected_tracks: List[Tuple[int, BoundingBox]] = []

        if not results or len(results) == 0:
            return detected_tracks

        r = results[0]
        boxes = r.boxes

        if boxes is None or len(boxes) == 0:
            return detected_tracks

        for box in boxes:
            # Lấy tọa độ (x1, y1, x2, y2)
            xyxy = box.xyxy[0].cpu().numpy()
            conf = float(box.conf[0].cpu().numpy()) if box.conf is not None else 1.0
            
            # Lấy track_id nếu có từ tracker, nếu chưa có (frame đầu) thì gán tạm -1
            track_id = int(box.id[0].cpu().numpy()) if box.id is not None else -1

            bbox = BoundingBox(
                x1=float(xyxy[0]),
                y1=float(xyxy[1]),
                x2=float(xyxy[2]),
                y2=float(xyxy[3]),
                confidence=conf
            )
            detected_tracks.append((track_id, bbox))

        return detected_tracks
