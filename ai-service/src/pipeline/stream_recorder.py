import os
import sys
import hashlib

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
for p_dir in DLL_SEARCH_PATHS:
    if os.path.exists(p_dir):
        if hasattr(os, "add_dll_directory"):
            try:
                os.add_dll_directory(p_dir)
            except Exception:
                pass
        if p_dir not in os.environ.get("PATH", ""):
            os.environ["PATH"] = p_dir + os.pathsep + os.environ.get("PATH", "")

import cv2
import time
import queue
import logging
import threading
from datetime import datetime, timezone, timedelta
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)

VN_TZ = timezone(timedelta(hours=7))


def _now_vn() -> datetime:
    return datetime.now(VN_TZ)


def _compute_sha256(file_path: str) -> Optional[str]:
    try:
        h = hashlib.sha256()
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                h.update(chunk)
        return h.hexdigest()
    except Exception:
        return None


def build_recording_completed_payload(
    camera_code: str,
    recording_type: str,
    bucket_name: str,
    object_key: str,
    file_path: str,
    start_dt: datetime,
    end_dt: datetime,
    frames_count: int,
    fps: float,
    incident_event_id: Optional[str] = None,
    resolution: str = "854x480",
) -> Dict[str, Any]:
    file_size = os.path.getsize(file_path) if file_path and os.path.exists(file_path) else 0
    actual_duration = max(1, int(round(frames_count / max(fps, 1.0))))
    checksum = _compute_sha256(file_path) if file_size > 0 else None

    if start_dt.tzinfo is None:
        start_dt = start_dt.replace(tzinfo=VN_TZ)
    if end_dt.tzinfo is None:
        end_dt = end_dt.replace(tzinfo=VN_TZ)

    return {
        "event_type": "RECORDING_COMPLETED",
        "camera_code": camera_code.strip().upper(),
        "recording_type": recording_type,
        "storage_location": "CLOUD_MINIO",
        "bucket_name": bucket_name,
        "object_key": object_key,
        "start_time": start_dt.isoformat(),
        "end_time": end_dt.isoformat(),
        "duration_seconds": actual_duration,
        "file_size_bytes": file_size,
        "checksum_sha256": checksum,
        "fps": int(round(fps)),
        "resolution": resolution,
        "incident_event_id": incident_event_id,
    }


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
    Module ghi video liên tục chuẩn giám sát an ninh (Edge-Cloud Optimized):
    - Chuẩn hóa độ phân giải lưu trữ: 854x480 (480p 16:9) @ 15 FPS (~5-8 MB/phút).
    - Đóng dấu OSD thời gian thực (CAM-xxx | dd/MM/yyyy HH:mm:ss) trực tiếp lên từng giây khung hình.
    - Ghi tạm vào file .recording.mp4 để tránh lộ file chưa có moov atom lên giao diện.
    - Khi hoàn tất 1 phút, đóng VideoWriter, đẩy lên MinIO S3, phát sự kiện MQTT `RECORDING_COMPLETED`
      với thời lượng và mốc giờ thật về Cloud PostgreSQL, đồng thời xoay vòng tối đa 30 phân đoạn.
    """

    def __init__(
        self,
        camera_code: str,
        base_dir: str = r"C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\recording",
        segment_seconds: int = 60,
        fps: float = 15.0,
    ):
        self.camera_code = camera_code.strip().upper()
        self.base_dir = os.path.abspath(base_dir)
        self.segment_seconds = segment_seconds
        self.fps = fps
        self.target_w = 854
        self.target_h = 480
        self.target_frames_per_segment = int(self.segment_seconds * self.fps)

        self.writer: Optional[cv2.VideoWriter] = None
        self.current_file_path: Optional[str] = None
        self.temp_file_path: Optional[str] = None
        self.frame_size: tuple = (self.target_w, self.target_h)
        self.frames_in_segment = 0
        self.segment_start_ts = 0.0
        self.segment_start_dt: Optional[datetime] = None
        self.last_frame = None
        self.last_frame_ts = 0.0
        self.last_enqueue_ts = 0.0
        self._writer_lock = threading.Lock()

        self.fourcc = cv2.VideoWriter_fourcc(*"avc1")
        self.frame_queue: queue.Queue = queue.Queue(maxsize=60)
        self.is_running = False
        self.worker_thread: Optional[threading.Thread] = None

    def start(self):
        if self.is_running:
            return
        self.is_running = True
        self.worker_thread = threading.Thread(target=self._recording_loop, daemon=True)
        self.worker_thread.start()
        logger.info(f"📹 [Recorder] Bắt đầu luồng ghi hình liên tục (854x480 @ {self.fps}fps) cho camera [{self.camera_code}].")

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

    def _draw_osd_timestamp(self, frame, now_dt: datetime):
        """Đóng dấu đồng hồ thời gian thực Việt Nam (dd/MM/yyyy HH:mm:ss) sắc nét ở góc trái trên"""
        vis = frame.copy()
        text = f"CAM: {self.camera_code} | {now_dt.strftime('%d/%m/%Y %H:%M:%S')}"
        cv2.rectangle(vis, (8, 8), (335, 32), (15, 15, 20), -1)
        cv2.putText(
            vis,
            text,
            (14, 24),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.46,
            (240, 240, 240),
            1,
            cv2.LINE_AA,
        )
        return vis

    def write_frame(self, frame):
        if frame is None or frame.size == 0 or not self.is_running:
            return

        now = time.time()
        if (now - self.last_enqueue_ts) < (0.85 / self.fps):
            return
        self.last_enqueue_ts = now

        try:
            if self.frame_queue.full():
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
            if (frame.shape[1], frame.shape[0]) != (self.target_w, self.target_h):
                small_frame = cv2.resize(frame, (self.target_w, self.target_h))
            else:
                small_frame = frame.copy()
            self.frame_queue.put_nowait((now, small_frame))
        except Exception:
            pass

    def _open_new_segment(self, ts: float):
        self._close_writer()
        with self._writer_lock:
            now_dt = _now_vn()
            self.segment_start_dt = now_dt
            self.current_file_path = self._get_target_file_path(now_dt)
            self.temp_file_path = self.current_file_path[:-4] + ".recording.mp4"
            self.frame_size = (self.target_w, self.target_h)
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
                logger.info(f"📹 [Recorder] Bắt đầu phiên ghi hình 1 phút (854x480): {self.current_file_path}")
            except Exception as e:
                logger.error(f"❌ [Recorder] Không thể mở VideoWriter ({self.temp_file_path}): {e}")
                self.writer = None

    def _recording_loop(self):
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
                ts, raw_small = item
            else:
                ts, raw_small = time.time(), item

            if raw_small is None or raw_small.size == 0:
                continue

            if (raw_small.shape[1], raw_small.shape[0]) != (self.target_w, self.target_h):
                raw_small = cv2.resize(raw_small, (self.target_w, self.target_h))

            if self.writer is None:
                self._open_new_segment(ts)

            if self.writer is None:
                continue

            frame_dt = datetime.fromtimestamp(ts, tz=VN_TZ)
            stamped_frame = self._draw_osd_timestamp(raw_small, frame_dt)
            expected_frame_idx = int((ts - self.segment_start_ts) * self.fps)

            with self._writer_lock:
                if self.writer is None:
                    continue
                if expected_frame_idx > self.frames_in_segment and self.last_frame is not None:
                    gap = min(expected_frame_idx - self.frames_in_segment, 6)
                    for _ in range(gap):
                        if self.frames_in_segment < self.target_frames_per_segment:
                            self.writer.write(self.last_frame)
                            self.frames_in_segment += 1
                        else:
                            break

                if self.frames_in_segment < self.target_frames_per_segment:
                    if expected_frame_idx >= self.frames_in_segment:
                        self.writer.write(stamped_frame)
                        self.frames_in_segment += 1
                    self.last_frame = stamped_frame

            self.last_frame_ts = ts

            elapsed_time = ts - self.segment_start_ts
            if self.frames_in_segment >= self.target_frames_per_segment or elapsed_time >= self.segment_seconds:
                self._pad_and_close()
                if self.is_running:
                    self._open_new_segment(ts)
                    with self._writer_lock:
                        if self.writer is not None:
                            self.writer.write(stamped_frame)
                            self.frames_in_segment = 1
                            self.last_frame = stamped_frame

        self._close_writer()

    def _pad_and_close(self):
        with self._writer_lock:
            if self.is_running and self.writer is not None and self.last_frame is not None:
                while self.frames_in_segment < self.target_frames_per_segment:
                    self.writer.write(self.last_frame)
                    self.frames_in_segment += 1
        self._close_writer()

    def _close_writer(self):
        with self._writer_lock:
            if self.writer is None:
                return
            writer_ref = self.writer
            temp_path = self.temp_file_path
            final_path = self.current_file_path
            cam_code = self.camera_code
            frames_written = self.frames_in_segment
            start_dt = self.segment_start_dt or _now_vn()
            end_dt = _now_vn()

            self.writer = None
            self.temp_file_path = None
            self.current_file_path = None
            self.frames_in_segment = 0
            self.segment_start_dt = None
            self.last_frame = None

            try:
                writer_ref.release()
                if temp_path and os.path.exists(temp_path):
                    if frames_written > 0 and _has_valid_moov_atom(temp_path):
                        os.replace(temp_path, final_path)
                        file_size = os.path.getsize(final_path)
                        logger.info(
                            f"💾 [Recorder] Đã lưu video định kỳ (854x480): {final_path} "
                            f"({round(file_size / (1024 * 1024), 2)} MB - {frames_written} frames)"
                        )
                        self._schedule_minio_upload(final_path, cam_code, start_dt, end_dt, frames_written)
                    else:
                        logger.warning(f"⚠️ [Recorder] Bỏ qua file video chưa hoàn chỉnh (thiếu moov atom): {temp_path}")
                        try:
                            os.remove(temp_path)
                        except Exception:
                            pass
            except Exception as e:
                logger.warning(f"⚠️ [Recorder] Lỗi khi giải phóng VideoWriter: {e}")

    def _schedule_minio_upload(
        self,
        file_path: str,
        camera_code: str,
        start_dt: datetime,
        end_dt: datetime,
        frames_written: int,
    ):
        def _upload_task():
            try:
                from ..integration.storage_service import storage_service
                from ..integration.mqtt_client import edge_mqtt_client
                from ..config import settings

                norm_path = os.path.normpath(file_path)
                filename = os.path.basename(norm_path)
                obj_name = f"{camera_code}/{start_dt.strftime('%Y/%m/%d')}/{filename}"
                bucket = getattr(settings, "MINIO_BUCKET_RECORDINGS", "camera-recordings")

                telemetry_payload = build_recording_completed_payload(
                    camera_code=camera_code,
                    recording_type="PERIODIC_SEGMENT",
                    bucket_name=bucket,
                    object_key=obj_name,
                    file_path=file_path,
                    start_dt=start_dt,
                    end_dt=end_dt,
                    frames_count=frames_written,
                    fps=self.fps,
                    incident_event_id=None,
                )

                success = storage_service.upload_video_file(file_path, bucket, obj_name)
                if success:
                    edge_mqtt_client.publish_recording_event(telemetry_payload)
                    try:
                        os.remove(file_path)
                        logger.info(f"🗑️ [Recorder Cleanup] Đã xóa file đệm cục bộ sau khi đẩy MinIO: {file_path}")
                    except Exception as ex:
                        logger.warning(f"Không thể xóa file tạm {file_path}: {ex}")

                    deleted_keys = storage_service.enforce_recording_retention(camera_code, max_segments=30)
                    for del_key in (deleted_keys or []):
                        edge_mqtt_client.publish_recording_event({
                            "event_type": "RECORDING_DELETED",
                            "camera_code": camera_code,
                            "object_key": del_key,
                        })
            except Exception as e:
                logger.error(f"❌ [Recorder MinIO Upload Task] Lỗi: {e}")

        threading.Thread(target=_upload_task, daemon=True).start()

    def stop(self):
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
