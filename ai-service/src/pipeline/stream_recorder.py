import os
import sys
import cv2
import time
import queue
import logging
import threading
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)

# Đảm bảo Windows tìm được thư viện OpenH264 để mã hóa chuẩn AVC1/H.264
DLL_SEARCH_PATHS = [
    os.path.dirname(os.path.abspath(__file__)),
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
    os.getcwd(),
]
for p in DLL_SEARCH_PATHS:
    if os.path.exists(p) and hasattr(os, "add_dll_directory"):
        try:
            os.add_dll_directory(p)
        except Exception:
            pass


class StreamRecorder:
    """
    Module ghi video liên tục chuẩn giám sát an ninh:
    - Lưu tại: C:\\DATN\\AI-Based-Intelligent-Campus-Security-Surveillance-System\\recording\\{camera_id}\\{year}\\{month}\\{day}\\...
    - Chuẩn hoá độ dài: Mỗi video kéo dài CHÍNH XÁC 1 phút (60.00 giây = 1500 frames @ 25 FPS) bất kể framerate camera.
    - Tự động cắt phân đoạn ngay sau 1 phút và mở ngay phân đoạn tiếp theo.
    - Sử dụng codec H.264 (avc1) tương thích trực tiếp với trình duyệt HTML5.
    """

    def __init__(
        self,
        camera_code: str,
        base_dir: str = r"C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\recording",
        segment_seconds: int = 60,
        fps: float = 25.0,
    ):
        self.camera_code = camera_code.strip()
        self.base_dir = os.path.abspath(base_dir)
        self.segment_seconds = segment_seconds
        self.fps = fps
        self.target_frames_per_segment = int(self.segment_seconds * self.fps)  # 60s * 25fps = 1500 frames

        self.writer: Optional[cv2.VideoWriter] = None
        self.current_file_path: Optional[str] = None
        self.frame_size: Optional[tuple] = None
        self.frames_in_segment = 0
        self.segment_start_ts = 0.0
        self.last_frame = None
        self.last_frame_ts = 0.0

        # Ưu tiên codec H.264 (avc1)
        self.fourcc = cv2.VideoWriter_fourcc(*"avc1")
        try:
            test_w = cv2.VideoWriter("_h264_test.mp4", self.fourcc, 1, (64, 64))
            if test_w.isOpened():
                test_w.release()
                if os.path.exists("_h264_test.mp4"):
                    os.remove("_h264_test.mp4")
            else:
                self.fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        except Exception:
            self.fourcc = cv2.VideoWriter_fourcc(*"mp4v")

        # Hàng đợi frame (non-blocking để capture thread không bị lag)
        self.frame_queue: queue.Queue = queue.Queue(maxsize=150)
        self.is_running = False
        self.worker_thread: Optional[threading.Thread] = None

    def start(self):
        """Khởi động luồng ghi video chạy ngầm"""
        if self.is_running:
            return
        self.is_running = True
        self.worker_thread = threading.Thread(target=self._recording_loop, daemon=True)
        self.worker_thread.start()
        logger.info(f"📹 [Recorder] Bắt đầu luồng ghi hình liên tục cho camera [{self.camera_code}].")

    def _get_target_file_path(self, now: datetime) -> str:
        """
        Cấu trúc thư mục theo đúng yêu cầu:
        C:\\DATN\\AI-Based-Intelligent-Campus-Security-Surveillance-System\\recording\\
        └── {camera_id}\\
            └── current year\\
                └── current month\\
                    └── current day\\
                        ├── day-month-year - hour-min-sec.mp4
        """
        year_str = now.strftime("%Y")
        month_str = now.strftime("%m")
        day_str = now.strftime("%d")

        # Windows cấm ký tự '/' và ':' trong tên file, định dạng an toàn cho Windows OS:
        # e.g: "24-10-2026 - 12-30-00.mp4" (đại diện cho 24/10/2026-12:30:00)
        filename = now.strftime("%d-%m-%Y - %H-%M-%S.mp4")

        folder_path = os.path.join(
            self.base_dir,
            self.camera_code,
            year_str,
            month_str,
            day_str,
        )
        os.makedirs(folder_path, exist_ok=True)
        return os.path.join(folder_path, filename)

    def write_frame(self, frame):
        """Đẩy frame vào hàng đợi nền (non-blocking)"""
        if frame is None or frame.size == 0 or not self.is_running:
            return

        try:
            if self.frame_queue.full():
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
            self.frame_queue.put_nowait((time.time(), frame.copy()))
        except Exception:
            pass

    def _open_new_segment(self, ts: float, frame_size: tuple):
        """Mở file video mới cho phân đoạn tiếp theo"""
        self._close_writer()
        now_dt = datetime.now()
        self.current_file_path = self._get_target_file_path(now_dt)
        self.frame_size = frame_size
        self.segment_start_ts = ts
        self.frames_in_segment = 0

        try:
            self.writer = cv2.VideoWriter(
                self.current_file_path,
                self.fourcc,
                self.fps,
                self.frame_size,
            )
            logger.info(f"📹 [Recorder] Bắt đầu phiên ghi hình 1 phút: {self.current_file_path}")
        except Exception as e:
            logger.error(f"❌ [Recorder] Không thể mở VideoWriter ({self.current_file_path}): {e}")
            self.writer = None

    def _recording_loop(self):
        """
        Vòng lặp ngầm:
        Lấy frame từ hàng đợi, chuẩn hoá thời gian CFR 25 FPS và cắt CHÍNH XÁC mỗi 1 phút (1500 frames).
        """
        while self.is_running or not self.frame_queue.empty():
            try:
                item = self.frame_queue.get(timeout=0.2)
            except queue.Empty:
                # Nếu không có frame mới nhưng đã trôi qua hơn 60s, hoàn tất segment hiện tại
                now = time.time()
                if self.writer is not None and (now - self.segment_start_ts) >= self.segment_seconds:
                    self._pad_and_close()
                continue

            if item is None:
                break

            if isinstance(item, tuple):
                ts, frame = item
            else:
                ts, frame = time.time(), item

            if frame is None or frame.size == 0:
                continue

            h, w = frame.shape[:2]

            # Khởi tạo phân đoạn đầu tiên hoặc khi kích thước thay đổi
            if self.writer is None or self.frame_size != (w, h):
                self._open_new_segment(ts, (w, h))

            if self.writer is None:
                continue

            # Tính số frame lý tưởng đã trôi qua dựa theo thời gian thực (CFR normalization)
            expected_frame_idx = int((ts - self.segment_start_ts) * self.fps)

            # 1. Bù khung hình lặp nếu mạng bị rớt / camera cấp frame chậm để video không bị tua nhanh
            if expected_frame_idx > self.frames_in_segment and self.last_frame is not None:
                gap = min(expected_frame_idx - self.frames_in_segment, 10)
                for _ in range(gap):
                    if self.frames_in_segment < self.target_frames_per_segment:
                        self.writer.write(self.last_frame)
                        self.frames_in_segment += 1
                    else:
                        break

            # 2. Ghi khung hình hiện tại nếu chưa đủ 1500 frames
            if self.frames_in_segment < self.target_frames_per_segment:
                if expected_frame_idx >= self.frames_in_segment:
                    self.writer.write(frame)
                    self.frames_in_segment += 1
                self.last_frame = frame

            self.last_frame_ts = ts

            # 3. Khi đã ghi đủ 1 phút (1500 frames = 60.00 giây) hoặc thời gian thực trôi qua >= 60s:
            # Cắt phân đoạn ngay và mở ngay phiên ghi hình mới không làm rơi khung hình!
            elapsed_time = ts - self.segment_start_ts
            if self.frames_in_segment >= self.target_frames_per_segment or elapsed_time >= self.segment_seconds:
                self._pad_and_close()
                # Mở ngay phiên ghi hình mới với frame hiện tại
                self._open_new_segment(ts, (w, h))
                if self.writer is not None:
                    self.writer.write(frame)
                    self.frames_in_segment = 1
                    self.last_frame = frame

        self._pad_and_close()

    def _pad_and_close(self):
        """Đảm bảo phân đoạn đủ đúng 1500 frames (60.00 giây) trước khi đóng file"""
        if self.writer is not None and self.last_frame is not None:
            while self.frames_in_segment < self.target_frames_per_segment:
                self.writer.write(self.last_frame)
                self.frames_in_segment += 1
        self._close_writer()

    def _close_writer(self):
        """Đóng file và giải phóng tài nguyên với moov atom hoàn chỉnh"""
        if self.writer is not None:
            try:
                self.writer.release()
                if self.current_file_path and os.path.exists(self.current_file_path):
                    file_size = os.path.getsize(self.current_file_path)
                    logger.info(
                        f"💾 [Recorder] Đã lưu video 1 phút: {self.current_file_path} "
                        f"({round(file_size / (1024 * 1024), 2)} MB - {self.frames_in_segment} frames)"
                    )
            except Exception as e:
                logger.warning(f"⚠️ [Recorder] Lỗi khi giải phóng VideoWriter: {e}")
            finally:
                self.writer = None
                self.current_file_path = None
                self.frames_in_segment = 0
                self.last_frame = None

    def stop(self):
        """Dừng hoàn toàn recorder"""
        if not self.is_running:
            return
        self.is_running = False
        try:
            self.frame_queue.put_nowait(None)
        except Exception:
            pass
        if self.worker_thread and self.worker_thread.is_alive():
            self.worker_thread.join(timeout=2.0)
            self.worker_thread = None
        self._pad_and_close()
        logger.info(f"🛑 [Recorder] Đã dừng ghi hình cho camera [{self.camera_code}].")

    def close(self):
        self.stop()
