import os
import cv2
import logging
import numpy as np
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

try:
    import av
    HAS_AV = True
except ImportError:
    HAS_AV = False
    logger.warning("⚠️ Thư viện 'av' (PyAV) chưa được cài đặt. Sẽ fallback sang cv2.VideoWriter.")


class Fmp4VideoWriter:
    """
    VideoWriter chuẩn Fragmented MP4 (fMP4 - RFC/ISO 14496-12) cho hệ thống an ninh:
    - Sử dụng PyAV (FFmpeg C bindings) với bộ mã hóa libx264.
    - Cấu hình cờ: `movflags=frag_keyframe+empty_moov+default_base_moof`.
    - moov atom rỗng được ghi ngay tại byte đầu tiên của file khi bắt đầu ghi.
    - Mỗi GOP (15 frame / 1 giây @ 15fps) tạo một moof/mdat fragment hoàn chỉnh và flush ngay xuống đĩa.
    - Cho phép xem video trực tiếp khi đang ghi dở (không cần chờ kết thúc, không làm hỏng file nếu mất điện).
    - Tự động fallback sang cv2.VideoWriter nếu PyAV không khả dụng.
    """

    def __init__(
        self,
        file_path: str,
        fps: float = 15.0,
        frame_size: Tuple[int, int] = (854, 480),
        keyframe_interval: Optional[int] = None,
    ):
        self.file_path = os.path.abspath(file_path)
        self.fps = max(1.0, float(fps))
        self.target_w, self.target_h = frame_size
        self.gop = keyframe_interval if keyframe_interval is not None else int(round(self.fps))

        self._raw_file = None
        self._container = None
        self._stream = None
        self._cv2_writer: Optional[cv2.VideoWriter] = None
        self._is_opened = False
        self._frames_written = 0

        self._init_writer()

    def _init_writer(self):
        # Đảm bảo thư mục cha tồn tại
        os.makedirs(os.path.dirname(self.file_path), exist_ok=True)

        if HAS_AV:
            try:
                self._raw_file = open(self.file_path, "wb")
                self._container = av.open(
                    self._raw_file,
                    mode="w",
                    format="mp4",
                    options={
                        "movflags": "frag_keyframe+empty_moov+default_base_moof",
                        "flush_packets": "1",
                    },
                )
                # Dùng libx264 tương thích tối đa trên mọi trình duyệt Web/HTML5 video
                self._stream = self._container.add_stream("libx264", rate=int(round(self.fps)))
                self._stream.width = self.target_w
                self._stream.height = self.target_h
                self._stream.pix_fmt = "yuv420p"
                self._stream.options = {
                    "preset": "ultrafast",
                    "tune": "zerolatency",
                    "g": str(self.gop),
                }
                self._is_opened = True
                logger.debug(f"🎬 [Fmp4Writer] Đã mở fMP4 container thành công: {self.file_path}")
                return
            except Exception as e:
                logger.warning(f"⚠️ [Fmp4Writer] Không thể khởi tạo PyAV fMP4 ({e}), fallback sang cv2.VideoWriter")
                self._cleanup_av()

        # Fallback sang cv2.VideoWriter
        try:
            self._cv2_writer = cv2.VideoWriter(
                self.file_path,
                cv2.VideoWriter_fourcc(*"avc1"),
                self.fps,
                (self.target_w, self.target_h),
            )
            if self._cv2_writer is None or not self._cv2_writer.isOpened():
                if os.name == "nt" and hasattr(cv2, "CAP_MSMF"):
                    self._cv2_writer = cv2.VideoWriter(
                        self.file_path,
                        cv2.CAP_MSMF,
                        cv2.VideoWriter_fourcc(*"avc1"),
                        self.fps,
                        (self.target_w, self.target_h),
                    )
            if self._cv2_writer is None or not self._cv2_writer.isOpened():
                self._cv2_writer = cv2.VideoWriter(
                    self.file_path,
                    cv2.VideoWriter_fourcc(*"mp4v"),
                    self.fps,
                    (self.target_w, self.target_h),
                )
            self._is_opened = self._cv2_writer is not None and self._cv2_writer.isOpened()
        except Exception as e:
            logger.error(f"❌ [Fmp4Writer] Fallback cv2.VideoWriter thất bại: {e}")
            self._is_opened = False

    def isOpened(self) -> bool:
        return self._is_opened

    def write(self, frame: np.ndarray) -> None:
        if not self._is_opened or frame is None or frame.size == 0:
            return

        if (frame.shape[1], frame.shape[0]) != (self.target_w, self.target_h):
            frame = cv2.resize(frame, (self.target_w, self.target_h))

        if self._stream is not None and self._container is not None:
            try:
                av_frame = av.VideoFrame.from_ndarray(frame, format="bgr24")
                for packet in self._stream.encode(av_frame):
                    self._container.mux(packet)
                if self._raw_file is not None:
                    self._raw_file.flush()
                self._frames_written += 1
            except Exception as ex:
                logger.warning(f"⚠️ [Fmp4Writer] Lỗi ghi frame qua PyAV: {ex}")
        elif self._cv2_writer is not None:
            try:
                self._cv2_writer.write(frame)
                self._frames_written += 1
            except Exception as ex:
                logger.warning(f"⚠️ [Fmp4Writer] Lỗi ghi frame qua cv2: {ex}")

    def release(self) -> None:
        if not self._is_opened:
            return
        self._is_opened = False

        if self._stream is not None and self._container is not None:
            try:
                for packet in self._stream.encode():
                    self._container.mux(packet)
            except Exception:
                pass
            self._cleanup_av()
        elif self._cv2_writer is not None:
            try:
                self._cv2_writer.release()
            except Exception:
                pass
            self._cv2_writer = None

    def _cleanup_av(self):
        if self._container is not None:
            try:
                self._container.close()
            except Exception:
                pass
            self._container = None
        if self._raw_file is not None:
            try:
                self._raw_file.close()
            except Exception:
                pass
            self._raw_file = None
        self._stream = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.release()
