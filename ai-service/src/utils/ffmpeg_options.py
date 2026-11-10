import os
from typing import Optional
from ..config import settings


def get_ffmpeg_capture_options(timeout_us: Optional[int] = None) -> str:
    """
    Tạo chuỗi tham số OPENCV_FFMPEG_CAPTURE_OPTIONS tối ưu độ trễ thấp (P1-06).
    Định dạng cho OpenCV FFmpeg backend: 'key;value|key;value|...'
    """
    options = []

    # 1. Transport protocol (mặc định tcp tránh mất gói UDP)
    transport = getattr(settings, "RTSP_TRANSPORT", "tcp") or "tcp"
    options.append(f"rtsp_transport;{transport}")

    # 2. Cờ nobuffer để triệt tiêu độ trễ đệm hàng đợi frame
    if getattr(settings, "FFMPEG_NOBUFFER", True):
        options.append("fflags;nobuffer")

    # 3. Cờ low_delay báo cho FFmpeg giảm tối đa latency giải mã
    if getattr(settings, "FFMPEG_LOW_DELAY", True):
        options.append("flags;low_delay")

    # 4. Timeout đọc socket
    actual_timeout = timeout_us if timeout_us is not None else getattr(settings, "FFMPEG_STIMEOUT_US", 5000000)
    options.append(f"stimeout;{actual_timeout}")

    return "|".join(options)
