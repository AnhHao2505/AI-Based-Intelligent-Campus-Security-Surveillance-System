import os
import sys
import cv2
import time
import queue
import logging
import threading
import numpy as np
from datetime import datetime
from typing import Optional, Dict, Any, List, Callable

from ..config import settings
from .stream_recorder import _has_valid_moov_atom, _now_vn, build_recording_completed_payload

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


class IncidentRecorder:
    """
    Module ghi hình sự cố an ninh (Incident Recording - Edge-Cloud Optimized):
    - Chuẩn hóa phân giải: 854x480 (480p 16:9) @ 15 FPS, tối ưu dung lượng (~5-8 MB/phút).
    - Ghi trực tiếp khung hình AI thật (annotated_frame) đã có Bounding Box, ROI Polygon và tên/mã đối tượng.
    - Viền cảnh báo tĩnh giúp bộ nén H.264 P-frame đạt hiệu suất nén tối đa.
    - Ghi tạm vào file .recording.mp4 để tránh lộ file đang ghi chưa đóng moov atom lên giao diện.
    - Khi kết thúc, tải lên MinIO S3 và đồng bộ metadata thật (start_time, end_time, duration_seconds, sha256,
      incident_event_id) qua MQTT `campus/recordings/completed` về PostgreSQL.
    """

    def __init__(
        self,
        incident_id: str,
        camera_code: str,
        event_type: str = "SECURITY_INCIDENT",
        details: str = "",
        base_dir: Optional[str] = None,
        fps: float = 15.0,
        frame_provider: Optional[Callable[[], Optional[np.ndarray]]] = None,
        max_duration_seconds: float = 60.0,
    ):
        self.incident_id = str(incident_id).strip()
        self.camera_code = camera_code.strip().upper()
        self.event_type = event_type
        self.details = details or "Phát hiện sự cố an ninh"
        self.base_dir = os.path.abspath(
            base_dir or getattr(settings, "EVIDENCE_BASE_DIR", r"C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\evidence")
        )
        self.fps = fps
        self.frame_provider = frame_provider
        self.max_duration_seconds = max_duration_seconds

        self.start_dt = _now_vn()
        self.start_ts = time.time()
        self.file_path = self._get_target_file_path(self.start_dt)
        self.temp_file_path = self.file_path[:-4] + ".recording.mp4"

        self.target_w = 854
        self.target_h = 480
        self.writer: Optional[cv2.VideoWriter] = None
        self.frames_written = 0
        self.is_running = False
        self._writer_lock = threading.Lock()
        self.frame_queue: queue.Queue = queue.Queue(maxsize=60)
        self.worker_thread: Optional[threading.Thread] = None

        self.fourcc = cv2.VideoWriter_fourcc(*"avc1")

    def _get_target_file_path(self, now: datetime) -> str:
        year_str = now.strftime("%Y")
        month_str = now.strftime("%m")
        day_str = now.strftime("%d")

        filename = f"Incident {now.strftime('%d-%m-%Y - %H-%M-%S')}.mp4"
        folder_path = os.path.join(
            self.base_dir,
            self.camera_code,
            year_str,
            month_str,
            day_str,
        )
        os.makedirs(folder_path, exist_ok=True)
        return os.path.join(folder_path, filename)

    def start(self):
        if self.is_running:
            return
        self.is_running = True

        with self._writer_lock:
            try:
                if os.name == "nt" and hasattr(cv2, "CAP_MSMF"):
                    self.writer = cv2.VideoWriter(
                        self.temp_file_path,
                        cv2.CAP_MSMF,
                        cv2.VideoWriter_fourcc(*"avc1"),
                        self.fps,
                        (self.target_w, self.target_h),
                    )
                if self.writer is None or not self.writer.isOpened():
                    self.writer = cv2.VideoWriter(
                        self.temp_file_path,
                        cv2.VideoWriter_fourcc(*"mp4v"),
                        self.fps,
                        (self.target_w, self.target_h),
                    )
                logger.info(
                    f"🚨 [IncidentRecorder] MỞ FILE GHI HÌNH SỰ CỐ camera [{self.camera_code}]: {self.file_path} @ 854x480 ({self.fps}fps)"
                )
            except Exception as e:
                logger.error(f"❌ [IncidentRecorder] Không thể mở VideoWriter: {e}")
                self.writer = None

        self.worker_thread = threading.Thread(target=self._recording_loop, daemon=True)
        self.worker_thread.start()

    def write_frame(self, frame: np.ndarray):
        if frame is None or frame.size == 0 or not self.is_running:
            return
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
            self.frame_queue.put_nowait(small_frame)
        except Exception:
            pass

    def _draw_incident_overlay_on_real_frame(self, frame: np.ndarray, elapsed: float, now: datetime) -> np.ndarray:
        w, h = self.target_w, self.target_h
        if (frame.shape[1], frame.shape[0]) != (w, h):
            vis_frame = cv2.resize(frame, (w, h))
        else:
            vis_frame = frame.copy()

        cv2.rectangle(vis_frame, (2, 2), (w - 2, h - 2), (0, 0, 220), 2)

        sec_int = int(elapsed)
        header_bg_w = 420
        cv2.rectangle(vis_frame, (8, 8), (header_bg_w, 34), (15, 15, 20), -1)
        header_text = f"CAM: {self.camera_code} | {now.strftime('%d/%m/%Y %H:%M:%S')} | REC {sec_int}s"
        cv2.putText(vis_frame, header_text, (14, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (240, 240, 240), 1, cv2.LINE_AA)

        banner_h = 32
        cv2.rectangle(vis_frame, (8, h - banner_h - 6), (w - 8, h - 6), (15, 15, 20), -1)
        cv2.rectangle(vis_frame, (8, h - banner_h - 6), (w - 8, h - 6), (0, 0, 220), 1)
        cv2.putText(
            vis_frame,
            f"SU CO AN NINH [{self.event_type}] - Camera: {self.camera_code}",
            (16, h - 16),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.48,
            (0, 140, 255),
            1,
            cv2.LINE_AA
        )
        return vis_frame

    def _recording_loop(self):
        frame_interval = 1.0 / self.fps
        next_frame_time = time.time()
        last_real_frame = None

        while self.is_running:
            now_ts = time.time()
            elapsed = now_ts - self.start_ts
            now_dt = _now_vn()

            if elapsed >= self.max_duration_seconds:
                logger.info(f"⏰ [IncidentRecorder] Camera [{self.camera_code}] chạm ngưỡng {self.max_duration_seconds}s. Tự động chốt file video sự cố.")
                break

            real_frame = None
            try:
                real_frame = self.frame_queue.get(timeout=0.05)
            except queue.Empty:
                pass

            if real_frame is None and self.frame_provider is not None:
                try:
                    real_frame = self.frame_provider()
                except Exception:
                    real_frame = None

            if real_frame is not None and real_frame.size > 0:
                last_real_frame = real_frame
                frame_to_write = self._draw_incident_overlay_on_real_frame(real_frame, elapsed, now_dt)
            elif last_real_frame is not None and last_real_frame.size > 0:
                frame_to_write = self._draw_incident_overlay_on_real_frame(last_real_frame, elapsed, now_dt)
            else:
                time.sleep(0.05)
                continue

            with self._writer_lock:
                if self.writer is not None and frame_to_write is not None:
                    self.writer.write(frame_to_write)
                    self.frames_written += 1

            next_frame_time += frame_interval
            sleep_time = next_frame_time - time.time()
            if sleep_time > 0:
                time.sleep(sleep_time)
            else:
                next_frame_time = time.time()

        self._close_writer()

    def _close_writer(self):
        with self._writer_lock:
            if self.writer is None:
                self.is_running = False
                return
            writer_ref = self.writer
            temp_path = self.temp_file_path
            final_path = self.file_path
            cam_code = self.camera_code
            start_dt = self.start_dt
            end_dt = _now_vn()
            frames_count = self.frames_written
            inc_id = self.incident_id

            self.writer = None
            self.is_running = False

            try:
                writer_ref.release()
                if temp_path and os.path.exists(temp_path):
                    if frames_count > 0 and _has_valid_moov_atom(temp_path):
                        os.replace(temp_path, final_path)
                        size_mb = os.path.getsize(final_path) / (1024 * 1024)
                        duration_s = frames_count / max(self.fps, 1.0)
                        logger.info(
                            f"✅ [IncidentRecorder] ĐÃ CHỐT XONG VIDEO BẰNG CHỨNG SỰ CỐ (854x480): {final_path} "
                            f"({size_mb:.2f} MB - {frames_count} frames - {duration_s:.1f}s)"
                        )
                        self._schedule_minio_upload(final_path, cam_code, start_dt, end_dt, frames_count, inc_id)
                    else:
                        logger.warning(f"⚠️ [IncidentRecorder] Bỏ qua file sự cố chưa hoàn chỉnh hoặc không có frame: {temp_path}")
                        try:
                            os.remove(temp_path)
                        except Exception:
                            pass
            except Exception as e:
                logger.warning(f"⚠️ [IncidentRecorder] Lỗi khi giải phóng VideoWriter: {e}")

    def _schedule_minio_upload(
        self,
        file_path: str,
        camera_code: str,
        start_dt: datetime,
        end_dt: datetime,
        frames_count: int,
        incident_event_id: str,
    ):
        def _upload_task():
            try:
                from ..integration.storage_service import storage_service
                from ..integration.mqtt_client import edge_mqtt_client
                from ..config import settings

                norm_path = os.path.normpath(file_path)
                filename = os.path.basename(norm_path)
                obj_name = f"{camera_code}/{start_dt.strftime('%Y/%m/%d')}/{filename}"
                bucket = getattr(settings, "MINIO_BUCKET_EVIDENCE", "security-evidence")

                telemetry_payload = build_recording_completed_payload(
                    camera_code=camera_code,
                    recording_type="INCIDENT_EVIDENCE",
                    bucket_name=bucket,
                    object_key=obj_name,
                    file_path=file_path,
                    start_dt=start_dt,
                    end_dt=end_dt,
                    frames_count=frames_count,
                    fps=self.fps,
                    incident_event_id=incident_event_id,
                )

                success = storage_service.upload_video_file(file_path, bucket, obj_name)
                if success:
                    edge_mqtt_client.publish_recording_event(telemetry_payload)
                    try:
                        os.remove(file_path)
                        logger.info(f"🗑️ [IncidentRecorder Cleanup] Đã xóa file đệm cục bộ sau khi đẩy MinIO: {file_path}")
                    except Exception as ex:
                        logger.warning(f"Không thể xóa file tạm {file_path}: {ex}")
            except Exception as e:
                logger.error(f"❌ [IncidentRecorder MinIO Upload Task] Lỗi: {e}")

        threading.Thread(target=_upload_task, daemon=True).start()

    def stop(self) -> Dict[str, Any]:
        if not self.is_running:
            return self.get_summary()

        self.is_running = False
        if self.worker_thread and self.worker_thread.is_alive():
            self.worker_thread.join(timeout=5.0)
            self.worker_thread = None

        self._close_writer()
        return self.get_summary()

    def get_summary(self) -> Dict[str, Any]:
        duration_s = round(self.frames_written / max(self.fps, 1.0), 1)
        file_size_bytes = os.path.getsize(self.file_path) if os.path.exists(self.file_path) else 0
        return {
            "incident_id": self.incident_id,
            "camera_code": self.camera_code,
            "event_type": self.event_type,
            "file_path": self.file_path,
            "duration_seconds": duration_s,
            "frames_count": self.frames_written,
            "file_size_bytes": file_size_bytes,
            "is_running": self.is_running,
            "started_at": self.start_dt.isoformat(),
        }


class IncidentRecordingManager:
    def __init__(self):
        self._recordings_by_camera: Dict[str, IncidentRecorder] = {}
        self._recordings_by_id: Dict[str, IncidentRecorder] = {}
        self._lock = threading.Lock()

    def start_incident_recording(
        self,
        incident_id: str,
        camera_code: str,
        event_type: str = "SECURITY_INCIDENT",
        details: str = "",
        frame_provider: Optional[Callable[[], Optional[np.ndarray]]] = None,
    ) -> IncidentRecorder:
        with self._lock:
            str_id = str(incident_id).strip()
            cam_code = camera_code.strip().upper()

            existing = self._recordings_by_camera.get(cam_code)
            if existing and existing.is_running:
                logger.info(f"ℹ️ Camera [{cam_code}] đang trong phiên ghi hình sự cố ({existing.file_path}). Giữ nguyên phiên hiện tại.")
                self._recordings_by_id[str_id] = existing
                return existing

            recorder = IncidentRecorder(
                incident_id=str_id,
                camera_code=cam_code,
                event_type=event_type,
                details=details,
                frame_provider=frame_provider,
            )
            recorder.start()
            self._recordings_by_camera[cam_code] = recorder
            self._recordings_by_id[str_id] = recorder
            return recorder

    def write_frame_for_camera(self, camera_code: str, frame: np.ndarray):
        cam_code = camera_code.strip().upper()
        with self._lock:
            rec = self._recordings_by_camera.get(cam_code)
            if rec and rec.is_running:
                rec.write_frame(frame)

    def stop_incident_recording(self, key: str) -> Optional[Dict[str, Any]]:
        clean_key = str(key).strip()
        cam_upper = clean_key.upper()
        target_rec: Optional[IncidentRecorder] = None

        with self._lock:
            if cam_upper in self._recordings_by_camera:
                target_rec = self._recordings_by_camera.pop(cam_upper, None)
            elif clean_key in self._recordings_by_id:
                target_rec = self._recordings_by_id.pop(clean_key, None)
                if target_rec:
                    self._recordings_by_camera.pop(target_rec.camera_code, None)

        if target_rec:
            with self._lock:
                to_del = [k for k, v in self._recordings_by_id.items() if v == target_rec]
                for k in to_del:
                    self._recordings_by_id.pop(k, None)
            return target_rec.stop()

        logger.info(f"ℹ️ [IncidentManager] Không tìm thấy phiên ghi hình hoạt động cho khóa [{key}].")
        return None

    def stop_camera_recordings(self, camera_code: str) -> List[Dict[str, Any]]:
        cam_upper = camera_code.strip().upper()
        res = self.stop_incident_recording(cam_upper)
        return [res] if res else []

    def stop_all_incident_recordings(self) -> List[Dict[str, Any]]:
        results = []
        with self._lock:
            active_list = list(set(self._recordings_by_camera.values()))
            self._recordings_by_camera.clear()
            self._recordings_by_id.clear()

        for rec in active_list:
            res = rec.stop()
            results.append(res)
        logger.info(f"🛑 [IncidentManager] Đã kết thúc toàn bộ {len(results)} phiên ghi hình sự cố.")
        return results

    def get_active_recordings(self) -> List[Dict[str, Any]]:
        with self._lock:
            active_set = list(set(self._recordings_by_camera.values()))
            return [rec.get_summary() for rec in active_set if rec.is_running]


incident_recording_manager = IncidentRecordingManager()
