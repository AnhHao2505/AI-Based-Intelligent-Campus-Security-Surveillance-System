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
_current_dir = os.path.dirname(os.path.abspath(__file__))
_ai_service_dir = os.path.dirname(os.path.dirname(_current_dir))
_repo_dir = os.path.dirname(_ai_service_dir)

DLL_SEARCH_PATHS = [
    _ai_service_dir,
    _current_dir,
    _repo_dir,
    os.getcwd(),
]
for p in DLL_SEARCH_PATHS:
    if os.path.exists(p):
        if hasattr(os, "add_dll_directory"):
            try:
                os.add_dll_directory(p)
            except Exception:
                pass
        if p not in os.environ.get("PATH", ""):
            os.environ["PATH"] = p + os.pathsep + os.environ.get("PATH", "")


def _has_valid_moov_atom(file_path: str) -> bool:
    """Kiểm tra file MP4 đã đóng hoàn chỉnh và có chứa moov atom hợp lệ hay chưa"""
    try:
        if not file_path or not os.path.exists(file_path):
            return False
        size = os.path.getsize(file_path)
        if size < 1024:
            return False
        with open(file_path, "rb") as f:
            head = f.read(min(65536, size))
            if b"moov" in head:
                return True
            if size > 65536:
                f.seek(max(0, size - 262144))
                tail = f.read()
                return b"moov" in tail
        return False
    except Exception:
        return False


class StreamRecorder:
    """
    Module ghi video liên tục chuẩn giám sát an ninh:
    - Ghi tạm vào file .recording.mp4 để tránh lộ file chưa có moov atom lên giao diện.
    - Chuẩn hoá độ dài: Mỗi video kéo dài CHÍNH XÁC 1 phút (60.00 giây = 1500 frames @ 25 FPS).
    - Khi hoàn tất 1 phút, đóng VideoWriter (chốt moov atom), đổi tên sang .mp4 và đẩy lên MinIO.
    - Sử dụng codec H.264 (avc1) qua CAP_MSMF trên Windows tương thích trực tiếp với trình duyệt HTML5.
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
        self.temp_file_path: Optional[str] = None
        self.frame_size: Optional[tuple] = None
        self.frames_in_segment = 0
        self.segment_start_ts = 0.0
        self.last_frame = None
        self.last_frame_ts = 0.0
        self._writer_lock = threading.Lock()

        # Ưu tiên codec H.264 (avc1) qua Windows Media Foundation (CAP_MSMF)
        self.fourcc = cv2.VideoWriter_fourcc(*"avc1")

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
        year_str = now.strftime("%Y")
        month_str = now.strftime("%m")
        day_str = now.strftime("%d")
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
        """Mở file video mới (.recording.mp4) cho phân đoạn tiếp theo"""
        self._close_writer()
        with self._writer_lock:
            now_dt = datetime.now()
            self.current_file_path = self._get_target_file_path(now_dt)
            self.temp_file_path = self.current_file_path[:-4] + ".recording.mp4"
            self.frame_size = frame_size
            self.segment_start_ts = ts
            self.frames_in_segment = 0

            try:
                if os.name == "nt" and hasattr(cv2, "CAP_MSMF"):
                    self.writer = cv2.VideoWriter(
                        self.temp_file_path,
                        cv2.CAP_MSMF,
                        cv2.VideoWriter_fourcc(*"avc1"),
                        self.fps,
                        self.frame_size,
                    )
                if self.writer is None or not self.writer.isOpened():
                    self.writer = cv2.VideoWriter(
                        self.temp_file_path,
                        cv2.VideoWriter_fourcc(*"mp4v"),
                        self.fps,
                        self.frame_size,
                    )
                logger.info(f"📹 [Recorder] Bắt đầu phiên ghi hình 1 phút: {self.current_file_path}")
            except Exception as e:
                logger.error(f"❌ [Recorder] Không thể mở VideoWriter ({self.temp_file_path}): {e}")
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

            if self.writer is None or self.frame_size != (w, h):
                self._open_new_segment(ts, (w, h))

            if self.writer is None:
                continue

            expected_frame_idx = int((ts - self.segment_start_ts) * self.fps)

            with self._writer_lock:
                if self.writer is None:
                    continue
                # 1. Bù khung hình lặp nếu mạng bị rớt / camera cấp frame chậm
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
            elapsed_time = ts - self.segment_start_ts
            if self.frames_in_segment >= self.target_frames_per_segment or elapsed_time >= self.segment_seconds:
                self._pad_and_close()
                if self.is_running:
                    self._open_new_segment(ts, (w, h))
                    with self._writer_lock:
                        if self.writer is not None:
                            self.writer.write(frame)
                            self.frames_in_segment = 1
                            self.last_frame = frame

        self._close_writer()

    def _pad_and_close(self):
        """Đảm bảo phân đoạn đủ đúng 1500 frames (60.00 giây) khi chạy bình thường trước khi đóng file"""
        with self._writer_lock:
            if self.is_running and self.writer is not None and self.last_frame is not None:
                while self.frames_in_segment < self.target_frames_per_segment:
                    self.writer.write(self.last_frame)
                    self.frames_in_segment += 1
        self._close_writer()

    def _close_writer(self):
        """Đóng file an toàn theo lock, đổi tên .recording.mp4 -> .mp4 với moov atom hoàn chỉnh"""
        with self._writer_lock:
            if self.writer is None:
                return
            writer_ref = self.writer
            temp_path = self.temp_file_path
            final_path = self.current_file_path
            cam_code = self.camera_code
            frames_written = self.frames_in_segment

            self.writer = None
            self.temp_file_path = None
            self.current_file_path = None
            self.frames_in_segment = 0
            self.last_frame = None

            try:
                writer_ref.release()
                if temp_path and os.path.exists(temp_path):
                    if _has_valid_moov_atom(temp_path):
                        os.replace(temp_path, final_path)
                        file_size = os.path.getsize(final_path)
                        logger.info(
                            f"💾 [Recorder] Đã lưu video 1 phút: {final_path} "
                            f"({round(file_size / (1024 * 1024), 2)} MB - {frames_written} frames)"
                        )
                        self._schedule_minio_upload(final_path, cam_code)
                    else:
                        logger.warning(f"⚠️ [Recorder] Bỏ qua file video chưa hoàn chỉnh (thiếu moov atom): {temp_path}")
                        try:
                            os.remove(temp_path)
                        except Exception:
                            pass
            except Exception as e:
                logger.warning(f"⚠️ [Recorder] Lỗi khi giải phóng VideoWriter: {e}")

    def _schedule_minio_upload(self, file_path: str, camera_code: str):
        """Đẩy video 1 phút lên MinIO trong luồng nền và dọn dẹp file tạm (Phương án A)"""
        def _upload_task():
            try:
                from ..integration.storage_service import storage_service
                from ..config import settings
                norm_path = os.path.normpath(file_path)
                filename = os.path.basename(norm_path)
                now = datetime.now()
                obj_name = f"{camera_code}/{now.strftime('%Y/%m/%d')}/{filename}"
                bucket = getattr(settings, "MINIO_BUCKET_RECORDINGS", "camera-recordings")
                success = storage_service.upload_video_file(file_path, bucket, obj_name)
                if success:
                    try:
                        os.remove(file_path)
                        logger.info(f"🗑️ [Recorder Cleanup] Đã xóa file đệm cục bộ sau khi đẩy MinIO: {file_path}")
                    except Exception as ex:
                        logger.warning(f"Không thể xóa file tạm {file_path}: {ex}")
            except Exception as e:
                logger.error(f"❌ [Recorder MinIO Upload Task] Lỗi: {e}")

        threading.Thread(target=_upload_task, daemon=True).start()

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
            self.worker_thread.join(timeout=5.0)
            self.worker_thread = None
        self._close_writer()
        logger.info(f"🛑 [Recorder] Đã dừng ghi hình cho camera [{self.camera_code}].")

    def close(self):
        self.stop()
