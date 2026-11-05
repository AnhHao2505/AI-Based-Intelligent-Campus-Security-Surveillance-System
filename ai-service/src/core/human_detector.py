import logging
import os
import torch
from typing import List, Tuple, Optional
import numpy as np
from ultralytics import YOLO
from .entity import BoundingBox

import tempfile
import yaml
from ..config import settings

# Cấu hình ByteTrack riêng cho giám sát (ai-service/config/), tính theo vị trí file thay vì đường dẫn tuyệt đối của một máy
_BYTETRACK_CONFIG_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "config",
    "bytetrack_surveillance.yaml",
)


def compute_track_buffer(analysis_fps: float, memory_seconds: float = 2.0) -> int:
    """
    Quy đổi track_buffer của ByteTrack theo thời gian lưu vết (mặc định 2.0 giây).
    - 30 FPS -> 60 frames.
    - 10 FPS -> 20 frames.
    - 12 FPS -> 24 frames.
    - Cận dưới an toàn: tối thiểu 5 frames.
    """
    if analysis_fps <= 0:
        return 60
    return max(5, int(round(analysis_fps * memory_seconds)))


def get_tracker_config_path(analysis_fps: Optional[float] = None) -> str:
    """
    Trả về đường dẫn cấu hình ByteTrack.
    Nếu analysis_fps được truyền và khác 30 FPS, tự động sinh file cấu hình tạm
    với track_buffer đã được quy đổi theo thời gian lưu vết 2.0s (P0-03).
    """
    base_path = _BYTETRACK_CONFIG_PATH if os.path.exists(_BYTETRACK_CONFIG_PATH) else "bytetrack.yaml"
    if analysis_fps is None or analysis_fps <= 0 or abs(analysis_fps - 30.0) < 0.5:
        return base_path

    target_buffer = compute_track_buffer(analysis_fps, memory_seconds=2.0)
    cache_dir = os.path.join(tempfile.gettempdir(), "bytetrack_configs")
    os.makedirs(cache_dir, exist_ok=True)
    custom_cfg_path = os.path.join(cache_dir, f"bytetrack_{int(round(analysis_fps))}fps.yaml")

    cfg_data = {
        "tracker_type": "bytetrack",
        "track_high_thresh": 0.25,
        "track_low_thresh": 0.10,
        "new_track_thresh": 0.25,
        "track_buffer": target_buffer,
        "match_thresh": 0.75,
        "fuse_score": True
    }
    if os.path.exists(base_path):
        try:
            with open(base_path, "r", encoding="utf-8") as f:
                loaded = yaml.safe_load(f)
                if isinstance(loaded, dict):
                    cfg_data.update(loaded)
                    cfg_data["track_buffer"] = target_buffer
        except Exception:
            pass
    with open(custom_cfg_path, "w", encoding="utf-8") as f:
        yaml.dump(cfg_data, f)

    return custom_cfg_path


logger = logging.getLogger(__name__)

class HumanDetector:
    """
    Module phát hiện & theo dõi con người sử dụng YOLO26 và thuật toán ByteTrack.
    Chỉ kích hoạt class 0 (Person) nhằm tối đa hóa hiệu năng và tốc độ xử lý Realtime.
    Tự động kích hoạt GPU CUDA (card rời) nếu có sẵn để giải phóng CPU.
    """
    def __init__(
        self,
        model_path: str = "yolo26n.pt",
        conf_threshold: float = 0.35,
        device: Optional[str] = None,
        analysis_fps: Optional[float] = None
    ):
        self.model_path = model_path
        self.conf_threshold = conf_threshold
        self.analysis_fps = analysis_fps if analysis_fps is not None else getattr(settings, "ANALYSIS_MAX_FPS", 10.0)
        self.tracker_config = get_tracker_config_path(self.analysis_fps)
        self.track_buffer = compute_track_buffer(self.analysis_fps)
        
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

    def detect(self, frame: np.ndarray) -> List[Tuple[int, BoundingBox]]:
        """
        Phát hiện người trong ảnh đơn lẻ không qua tracker (stateless detection).
        Không cập nhật trạng thái ByteTrack để không làm bẩn bộ đệm tracker của luồng video.
        """
        if frame is None or frame.size == 0:
            return []

        results = self.model(
            source=frame,
            classes=[0],
            conf=self.conf_threshold,
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

        for idx, box in enumerate(boxes):
            xyxy = box.xyxy[0].cpu().numpy()
            conf = float(box.conf[0].cpu().numpy()) if box.conf is not None else 1.0
            x1, y1, x2, y2 = float(xyxy[0]), float(xyxy[1]), float(xyxy[2]), float(xyxy[3])
            w, h = x2 - x1, y2 - y1
            if h < 25 or w < 12:
                continue
            aspect_ratio = h / max(1.0, w)
            min_ar = 0.15 if conf >= 0.60 else 0.20
            if aspect_ratio < min_ar or aspect_ratio > 7.0:
                continue
            detected_tracks.append((idx + 1, BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2, confidence=conf)))

        return detected_tracks

    def set_analysis_fps(self, fps: float):
        """Cập nhật FPS phân tích và đồng bộ track_buffer cho ByteTrack (P0-03)"""
        self.analysis_fps = fps
        self.tracker_config = get_tracker_config_path(fps)
        self.track_buffer = compute_track_buffer(fps)
        if hasattr(self.model, "predictor") and self.model.predictor is not None:
            for t in getattr(self.model.predictor, "trackers", []):
                if hasattr(t, "max_frames_lost"):
                    t.max_frames_lost = self.track_buffer
                if hasattr(t, "args") and hasattr(t.args, "track_buffer"):
                    t.args.track_buffer = self.track_buffer

    def reset_tracker(self):
        """Khởi tạo lại trạng thái ByteTrack tracker khi luồng video bị đứt kết nối hoặc chuyển camera"""
        try:
            if hasattr(self.model, "predictor") and self.model.predictor is not None:
                if hasattr(self.model.predictor, "trackers"):
                    self.model.predictor.trackers = []
            logger.info("🔄 [HumanDetector] ByteTrack tracker đã được reset trạng thái thành công.")
        except Exception as e:
            logger.debug(f"[HumanDetector] Không thể reset tracker: {e}")

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
            tracker=self.tracker_config,
            device=self.device,
            verbose=False
        )
        if hasattr(self.model, "predictor") and self.model.predictor is not None:
            for t in getattr(self.model.predictor, "trackers", []):
                if hasattr(t, "max_frames_lost") and t.max_frames_lost != self.track_buffer:
                    t.max_frames_lost = self.track_buffer
                    if hasattr(t, "args"):
                        t.args.track_buffer = self.track_buffer

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

            x1 = float(xyxy[0])
            y1 = float(xyxy[1])
            x2 = float(xyxy[2])
            y2 = float(xyxy[3])
            w = x2 - x1
            h = y2 - y1

            # Lọc nhiễu kích thước và tỷ lệ hình học phi thực tế của con người:
            # 1. Kích thước tối thiểu: Người trong camera giám sát tối thiểu 25px chiều cao và 12px chiều rộng
            if h < 25 or w < 12:
                continue

            # 2. Tỷ lệ khung hình (aspect ratio: height / width):
            # Hỗ trợ đầy đủ các tư thế con người: đứng, đi lại, ngồi xổm, cúi người nhặt đồ, bò sát sàn, trượt ngã
            aspect_ratio = h / max(1.0, w)
            # Khi YOLO nhận diện với độ tin cậy cao (>= 0.60), cho phép tỷ lệ dẹt tới 0.15 (người trườn/bò sát đất)
            min_ar = 0.15 if conf >= 0.60 else 0.20
            if aspect_ratio < min_ar or aspect_ratio > 7.0:
                continue

            bbox = BoundingBox(
                x1=x1,
                y1=y1,
                x2=x2,
                y2=y2,
                confidence=conf
            )
            detected_tracks.append((track_id, bbox))

        return detected_tracks
