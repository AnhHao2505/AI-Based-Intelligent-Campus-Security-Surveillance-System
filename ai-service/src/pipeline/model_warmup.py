import time
import logging
import numpy as np
from typing import Optional

logger = logging.getLogger(__name__)


def warmup_pipeline_models(pipeline) -> bool:
    """
    Khởi động (warm-up) trước các model deep learning (YOLO, YuNet, ArcFace)
    ngay khi khởi động AI Service bằng dummy input, loại bỏ độ trễ giật lag (frame age spike)
    cho những frame đầu tiên của camera (P1-06).
    """
    if pipeline is None:
        return False

    t_start = time.perf_counter()
    warmed_count = 0

    dummy_frame = np.zeros((640, 640, 3), dtype=np.uint8)
    dummy_face_crop = np.zeros((112, 112, 3), dtype=np.uint8)

    # 1. Warm-up YOLO26 Human Detector
    detector = getattr(pipeline, "human_detector", None) or getattr(pipeline, "detector", None)
    if detector:
        try:
            if hasattr(detector, "detect_and_track"):
                detector.detect_and_track(dummy_frame)
            elif hasattr(detector, "model") and detector.model:
                detector.model.predict(dummy_frame, verbose=False)
            warmed_count += 1
        except Exception as e:
            logger.warning(f"⚠️ [Warmup] Lỗi khi warm-up YOLO model: {e}")

    # 2. Warm-up YuNet Face Detector
    face_detector = getattr(pipeline, "face_detector", None)
    if face_detector:
        try:
            if hasattr(face_detector, "detect"):
                face_detector.detect(dummy_frame)
            warmed_count += 1
        except Exception as e:
            logger.warning(f"⚠️ [Warmup] Lỗi khi warm-up YuNet FaceDetector: {e}")

    # 3. Warm-up ArcFace Face Embedder
    face_embedder = getattr(pipeline, "face_embedder", None)
    if face_embedder:
        try:
            if hasattr(face_embedder, "get_embedding"):
                face_embedder.get_embedding(dummy_face_crop)
            warmed_count += 1
        except Exception as e:
            logger.warning(f"⚠️ [Warmup] Lỗi khi warm-up ArcFace FaceEmbedder: {e}")

    elapsed_ms = (time.perf_counter() - t_start) * 1000.0
    logger.info(f"🔥 [ModelWarmup] Đã hoàn thành warm-up {warmed_count} mô hình AI ({elapsed_ms:.1f}ms).")
    return True
