import os
import sys
import shutil
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
from typing import Optional, Dict, Any, List, Set

logger = logging.getLogger(__name__)

_ACTIVE_UPLOADS: Set[str] = set()
_ACTIVE_UPLOADS_LOCK = threading.Lock()

def mark_upload_active(path: str) -> bool:
    """Đánh dấu file đang được upload để tránh retry/upload trùng lặp"""
    norm = os.path.normpath(path)
    with _ACTIVE_UPLOADS_LOCK:
        if norm in _ACTIVE_UPLOADS:
            return False
        _ACTIVE_UPLOADS.add(norm)
        return True

def mark_upload_finished(path: str):
    """Xóa đánh dấu khi upload hoàn tất hoặc thất bại"""
    norm = os.path.normpath(path)
    with _ACTIVE_UPLOADS_LOCK:
        _ACTIVE_UPLOADS.discard(norm)

def is_upload_active(path: str) -> bool:
    """Kiểm tra xem file có đang trong quá trình upload hay không"""
    norm = os.path.normpath(path)
    with _ACTIVE_UPLOADS_LOCK:
        return norm in _ACTIVE_UPLOADS


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


def enforce_local_recording_retention(
    directory: str,
    max_mb: Optional[int] = None,
    max_files: Optional[int] = None,
) -> List[str]:
    """
    Dọn dẹp các file video cục bộ cũ nhất khi MinIO offline để chống tràn ổ cứng (BUG-04).
    Hỗ trợ hạn ngạch theo dung lượng toàn cục (max_mb) hoặc số file (max_files).
    """
    removed = []
    if not directory or not os.path.exists(directory):
        return removed
    try:
        mp4_files = []
        for root, _, files in os.walk(directory):
            for fname in files:
                if fname.lower().endswith(".mp4") and not fname.endswith(".recording.mp4"):
                    full_p = os.path.join(root, fname)
                    if os.path.isfile(full_p):
                        try:
                            mtime = os.path.getmtime(full_p)
                            size = os.path.getsize(full_p)
                            mp4_files.append((full_p, mtime, size))
                        except Exception:
                            pass
        mp4_files.sort(key=lambda x: x[1])

        if max_mb is not None and max_mb > 0:
            max_bytes = max_mb * 1024 * 1024
            total_bytes = sum(f[2] for f in mp4_files)
            idx = 0
            while total_bytes > max_bytes and idx < len(mp4_files):
                p, _, sz = mp4_files[idx]
                try:
                    os.remove(p)
                    removed.append(p)
                    total_bytes -= sz
                    logger.warning(f"⚠️ [Disk Safeguard] Vượt hạn ngạch dung lượng ({max_mb} MB): Đã xóa file video cũ nhất {p}")
                except Exception as e:
                    logger.error(f"Lỗi khi xóa file {p}: {e}")
                idx += 1
        elif max_files is not None and max_files > 0:
            if len(mp4_files) > max_files:
                excess = len(mp4_files) - max_files
                for p, _, _ in mp4_files[:excess]:
                    try:
                        os.remove(p)
                        removed.append(p)
                        logger.warning(f"⚠️ [Disk Safeguard] MinIO offline: Đã tự động xóa file đệm video cũ nhất {p} để chống tràn đĩa.")
                    except Exception as e:
                        logger.error(f"Lỗi khi xóa file đệm cục bộ {p}: {e}")
    except Exception as e:
        logger.error(f"Lỗi thực hiện enforce_local_recording_retention: {e}")
    return removed


def enforce_emergency_evidence_retention(
    directory: str,
    emergency_free_mb: int = 500,
) -> List[str]:
    """
    Chỉ xóa video bằng chứng khi mức đĩa còn trống thấp hơn ngưỡng khẩn cấp (emergency_free_mb).
    Khi xóa bắt buộc phải log ERROR và phát MQTT system health event (BUG-04d).
    """
    removed = []
    if not directory or not os.path.exists(directory):
        return removed
    try:
        usage = shutil.disk_usage(directory)
        free_mb = usage.free / (1024 * 1024)
        if free_mb < emergency_free_mb:
            mp4_files = []
            for root, _, files in os.walk(directory):
                for fname in files:
                    if fname.lower().endswith(".mp4") and not fname.endswith(".recording.mp4"):
                        full_p = os.path.join(root, fname)
                        if os.path.isfile(full_p):
                            try:
                                mtime = os.path.getmtime(full_p)
                                mp4_files.append((full_p, mtime))
                            except Exception:
                                pass
            mp4_files.sort(key=lambda x: x[1])
            for p, _ in mp4_files:
                try:
                    os.remove(p)
                    removed.append(p)
                    logger.error(f"🚨 [EMERGENCY DISK FULL] Dung lượng đĩa còn {free_mb:.1f} MB (< {emergency_free_mb} MB)! Buộc phải xóa video bằng chứng: {p}")
                    # Phát MQTT health event thông báo sự cố bộ nhớ khẩn cấp
                    try:
                        from ..integration.mqtt_client import get_security_mqtt_client
                        mqtt = get_security_mqtt_client()
                        if mqtt is not None:
                            mqtt.publish_json(
                                "campus/system/health",
                                {
                                    "event_type": "SYSTEM_STORAGE_EMERGENCY",
                                    "severity": "CRITICAL",
                                    "message": f"Dung lượng đĩa khẩn cấp {free_mb:.1f} MB (< {emergency_free_mb} MB)! Đã xóa video bằng chứng: {os.path.basename(p)}",
                                    "file_deleted": p,
                                    "free_mb": round(free_mb, 1),
                                    "threshold_mb": emergency_free_mb,
                                    "timestamp": time.time(),
                                }
                            )
                    except Exception as hex:
                        logger.warning(f"Không thể phát MQTT health event: {hex}")

                    free_mb = shutil.disk_usage(directory).free / (1024 * 1024)
                    if free_mb >= emergency_free_mb:
                        break
                except Exception as e:
                    logger.error(f"Lỗi xóa file evidence khẩn cấp {p}: {e}")
    except Exception as e:
        logger.error(f"Lỗi thực hiện enforce_emergency_evidence_retention: {e}")
    return removed
    try:
        usage = shutil.disk_usage(directory)
        free_mb = usage.free / (1024 * 1024)
        if free_mb < emergency_free_mb:
            mp4_files = []
            for root, _, files in os.walk(directory):
                for fname in files:
                    if fname.lower().endswith(".mp4") and not fname.endswith(".recording.mp4"):
                        full_p = os.path.join(root, fname)
                        if os.path.isfile(full_p):
                            try:
                                mtime = os.path.getmtime(full_p)
                                mp4_files.append((full_p, mtime))
                            except Exception:
                                pass
            mp4_files.sort(key=lambda x: x[1])
            for p, _ in mp4_files:
                try:
                    os.remove(p)
                    removed.append(p)
                    logger.error(f"🚨 [EMERGENCY DISK FULL] Dung lượng đĩa còn {free_mb:.1f} MB (< {emergency_free_mb} MB)! Buộc phải xóa video bằng chứng: {p}")
                    free_mb = shutil.disk_usage(directory).free / (1024 * 1024)
                    if free_mb >= emergency_free_mb:
                        break
                except Exception as e:
                    logger.error(f"Lỗi xóa file evidence khẩn cấp {p}: {e}")
    except Exception as e:
        logger.error(f"Lỗi thực hiện enforce_emergency_evidence_retention: {e}")
    return removed


def retry_pending_uploads(
    storage_service=None,
    mqtt_client=None,
    recording_dir: Optional[str] = None,
    evidence_dir: Optional[str] = None,
    max_batch: int = 5,
    min_age_seconds: float = 5.0,
) -> int:
    """
    Job thử upload lại các file video (.mp4) còn tồn trên đĩa cục bộ khi MinIO hoạt động lại (BUG-04d).
    - Loại trừ file đang ghi dở (.recording.mp4 hoặc mtime < 5s)
    - Loại trừ file đang được upload (active upload set)
    - Sau khi upload thành công: gửi sự kiện MQTT và xóa file local.
    """
    if storage_service is None:
        try:
            from ..integration.storage_service import storage_service as _storage
            storage_service = _storage
        except Exception:
            return 0
    if mqtt_client is None:
        try:
            from ..integration.mqtt_client import get_security_mqtt_client
            mqtt_client = get_security_mqtt_client()
        except Exception:
            pass

    from ..config import settings
    rec_base = recording_dir or getattr(settings, "RECORDING_BASE_DIR", "")
    evid_base = evidence_dir or getattr(settings, "EVIDENCE_BASE_DIR", "")

    now_ts = time.time()
    pending_targets = []

    def _collect_files(base_dir: str, rec_type: str):
        if not base_dir or not os.path.exists(base_dir):
            return
        for root, _, files in os.walk(base_dir):
            for fname in files:
                # 1. Loại trừ file đang ghi dở theo tên
                if not fname.lower().endswith(".mp4") or fname.endswith(".recording.mp4") or fname.endswith(".tmp") or fname.endswith(".part"):
                    continue
                full_p = os.path.join(root, fname)
                if not os.path.isfile(full_p):
                    continue
                try:
                    mtime = os.path.getmtime(full_p)
                    # 2. Loại trừ file vừa được ghi / đang ghi dở (mtime < min_age_seconds)
                    if (now_ts - mtime) < min_age_seconds:
                        continue
                except Exception:
                    continue
                norm_p = os.path.normpath(full_p)
                # 3. Loại trừ file đang được upload
                if is_upload_active(norm_p):
                    continue
                pending_targets.append((norm_p, base_dir, rec_type))

    _collect_files(evid_base, "INCIDENT_EVIDENCE")
    _collect_files(rec_base, "PERIODIC_SEGMENT")

    uploaded = 0
    for file_path, base_d, rec_type in pending_targets:
        if uploaded >= max_batch:
            break
        try:
            if not os.path.exists(file_path):
                continue
            norm_path = os.path.normpath(file_path)
            # Khóa file để tránh tác vụ upload khác chạy đồng thời
            if not mark_upload_active(norm_path):
                continue

            try:
                rel_path = os.path.relpath(norm_path, base_d).replace(os.sep, "/")
                parts = rel_path.split("/")
                cam_code = parts[0] if parts else "UNKNOWN"
                obj_name = rel_path

                if rec_type == "INCIDENT_EVIDENCE":
                    bucket = getattr(settings, "MINIO_BUCKET_EVIDENCE", "security-evidence")
                else:
                    bucket = getattr(settings, "MINIO_BUCKET_RECORDINGS", "camera-recordings")

                success = storage_service.upload_video_file(norm_path, bucket, obj_name)
                if success:
                    mtime = os.path.getmtime(norm_path)
                    file_dt = datetime.fromtimestamp(mtime, tz=VN_TZ)
                    payload = build_recording_completed_payload(
                        camera_code=cam_code,
                        recording_type=rec_type,
                        bucket_name=bucket,
                        object_key=obj_name,
                        file_path=norm_path,
                        start_dt=file_dt - timedelta(seconds=60),
                        end_dt=file_dt,
                        frames_count=900,
                        fps=15.0,
                        incident_event_id=None,
                    )
                    if mqtt_client is not None:
                        try:
                            mqtt_client.publish_recording_event(payload)
                        except Exception as mex:
                            logger.warning(f"Lỗi publish MQTT sau retry upload: {mex}")
                    try:
                        os.remove(norm_path)
                        logger.debug(f"🗑️ [Retry Uploader] Đã xóa file local sau khi upload bù thành công: {norm_path}")
                    except Exception as rex:
                        logger.warning(f"Không thể xóa file {norm_path}: {rex}")
                    uploaded += 1
                    time.sleep(0.05)
                else:
                    break
            finally:
                mark_upload_finished(norm_path)
        except Exception as ex:
            logger.error(f"Lỗi khi retry upload file {file_path}: {ex}")
            break

    return uploaded


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
        self._stop_lock = threading.Lock()
        self._is_stopped = False

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
            # Bottleneck #3: Đẩy trực tiếp frame vào queue, việc resize (854x480) chạy ngầm trong _recording_loop
            self.frame_queue.put_nowait((now, frame))
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
                logger.debug(f"📹 [Recorder] Bắt đầu phiên ghi hình 1 phút (854x480): {self.current_file_path}")
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
            if not mark_upload_active(file_path):
                logger.warning(f"File {file_path} đang được upload, bỏ qua task trùng")
                return
            try:
                from ..integration.storage_service import storage_service
                from ..integration.mqtt_client import get_security_mqtt_client
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
                    get_security_mqtt_client().publish_recording_event(telemetry_payload)
                    try:
                        os.remove(file_path)
                        logger.debug(f"🗑️ [Recorder Cleanup] Đã xóa file đệm cục bộ sau khi đẩy MinIO: {file_path}")
                    except Exception as ex:
                        logger.warning(f"Không thể xóa file tạm {file_path}: {ex}")

                    deleted_keys = storage_service.enforce_recording_retention(camera_code, max_segments=30)
                    for del_key in (deleted_keys or []):
                        get_security_mqtt_client().publish_recording_event({
                            "event_type": "RECORDING_DELETED",
                            "camera_code": camera_code,
                            "object_key": del_key,
                        })
                else:
                    rec_dir = getattr(self, "base_dir", os.path.dirname(file_path))
                    max_mb = getattr(settings, "LOCAL_RECORDING_MAX_MB", 2048)
                    enforce_local_recording_retention(rec_dir, max_mb=max_mb)
            except Exception as e:
                logger.error(f"❌ [Recorder MinIO Upload Task] Lỗi: {e}")
            finally:
                mark_upload_finished(file_path)

        threading.Thread(target=_upload_task, daemon=True).start()

    def stop(self):
        with self._stop_lock:
            if not self.is_running and getattr(self, "_is_stopped", False):
                return
            self.is_running = False
            self._is_stopped = True
            try:
                self.frame_queue.put_nowait(None)
            except Exception:
                pass
            if self.worker_thread and self.worker_thread.is_alive():
                self.worker_thread.join(timeout=2.0)
                self.worker_thread = None
            self._close_writer()
            logger.info(f"🛑 [Recorder] Đã dừng ghi hình cho camera [{self.camera_code}].")

    def close(self):
        self.stop()
