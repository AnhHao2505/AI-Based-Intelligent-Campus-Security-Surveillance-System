import os
import cv2
from typing import Optional
from ..config import settings

_configured_threads: Optional[int] = None


def configure_system_threads(num_threads: Optional[int] = None) -> int:
    """
    Cấu hình số luồng xử lý CPU cho OMP, PyTorch, OpenCV và ONNX (P1-06).
    Thứ tự ưu tiên:
    1. Tham số num_threads truyền vào
    2. settings.AI_NUM_THREADS
    3. Biến môi trường AI_NUM_THREADS
    4. Biến môi trường OMP_NUM_THREADS
    5. Fallback an toàn: min(4, os.cpu_count() or 4)
    """
    global _configured_threads

    threads = num_threads
    if threads is None:
        threads = getattr(settings, "AI_NUM_THREADS", None)
    if threads is None:
        env_val = os.environ.get("AI_NUM_THREADS") or os.environ.get("OMP_NUM_THREADS")
        if env_val:
            try:
                threads = int(env_val)
            except ValueError:
                threads = None

    if threads is None or threads <= 0:
        cpu_cnt = os.cpu_count() or 4
        threads = min(4, cpu_cnt)

    # 1. Cấu hình biến môi trường OpenMP / MKL
    os.environ["OMP_NUM_THREADS"] = str(threads)
    os.environ["MKL_NUM_THREADS"] = str(threads)

    # 2. Cấu hình OpenCV CPU threads
    try:
        cv2.setNumThreads(threads)
    except Exception:
        pass

    # 3. Cấu hình PyTorch CPU threads (nếu có torch)
    try:
        import torch
        torch.set_num_threads(threads)
    except Exception:
        pass

    _configured_threads = threads
    return threads
