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
    Module ghi hình sự cố an ninh (Incident Recording):
    - Chuẩn hóa phân giải: 1280x720 (HD 25 FPS), tối ưu dung lượng và tải video mượt mà trên trình duyệt.
    - Kích hoạt khi phát hiện vi phạm hoặc khi Guard click Test sự cố.
    - Kết thúc khi Guard click 'Xác nhận đã xem' (hoặc tự động chốt file sau tối đa 5 phút để bảo vệ dữ liệu).
    - Lưu tại: C:\\DATN\\AI-Based-Intelligent-Campus-Security-Surveillance-System\\evidence\\{camera_code}\\{year}\\{month}\\{day}\\Incident dd-MM-yyyy - HH-mm-ss.mp4
    """

    def __init__(
        self,
        incident_id: str,
        camera_code: str,
        event_type: str = "SECURITY_INCIDENT",
        details: str = "",
        base_dir: Optional[str] = None,
        fps: float = 25.0,
        frame_provider: Optional[Callable[[], Optional[np.ndarray]]] = None,
        max_duration_seconds: float = 300.0, # 5 phút tự động chốt file nếu Guard quên bấm xác nhận
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

        self.start_dt = datetime.now()
        self.start_ts = time.time()
        self.file_path = self._get_target_file_path(self.start_dt)

        self.target_w = 1280
        self.target_h = 720
        self.writer: Optional[cv2.VideoWriter] = None
        self.frames_written = 0
        self.is_running = False
        self.frame_queue: queue.Queue = queue.Queue(maxsize=150)
        self.worker_thread: Optional[threading.Thread] = None

        # Codec H.264 avc1 (fallback mp4v)
        self.fourcc = cv2.VideoWriter_fourcc(*"avc1")
        try:
            test_w = cv2.VideoWriter("_inc_test.mp4", self.fourcc, 1, (64, 64))
            if test_w.isOpened():
                test_w.release()
                if os.path.exists("_inc_test.mp4"):
                    os.remove("_inc_test.mp4")
            else:
                self.fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        except Exception:
            self.fourcc = cv2.VideoWriter_fourcc(*"mp4v")

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
        """Khởi động luồng ghi hình sự cố"""
        if self.is_running:
            return
        self.is_running = True

        # Mở VideoWriter ngay từ đầu với kích thước cố định (1280x720)
        try:
            self.writer = cv2.VideoWriter(
                self.file_path,
                self.fourcc,
                self.fps,
                (self.target_w, self.target_h),
            )
            logger.info(
                f"🚨 [IncidentRecorder] MỞ FILE GHI HÌNH SỰ CỐ camera [{self.camera_code}]: {self.file_path} @ 1280x720"
            )
        except Exception as e:
            logger.error(f"❌ [IncidentRecorder] Không thể mở VideoWriter: {e}")
            self.writer = None

        self.worker_thread = threading.Thread(target=self._recording_loop, daemon=True)
        self.worker_thread.start()

    def write_frame(self, frame: np.ndarray):
        """Đẩy frame camera trực tiếp vào hàng đợi ghi hình"""
        if frame is None or frame.size == 0 or not self.is_running:
            return
        try:
            if self.frame_queue.full():
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
            self.frame_queue.put_nowait(frame.copy())
        except Exception:
            pass

    def _create_synthetic_incident_frame(self, elapsed: float, now: datetime) -> np.ndarray:
        w, h = self.target_w, self.target_h
        frame = np.full((h, w, 3), (24, 28, 36), dtype=np.uint8)

        # Lưới camera CCTV
        for x in range(0, w, 80):
            cv2.line(frame, (x, 0), (x, h), (32, 38, 48), 1)
        for y in range(0, h, 80):
            cv2.line(frame, (0, y), (w, y), (32, 38, 48), 1)

        # Viền nhấp nháy đỏ
        pulse = int((time.time() * 3) % 2)
        border_color = (0, 0, 230) if pulse == 0 else (0, 0, 140)
        cv2.rectangle(frame, (8, 8), (w - 8, h - 8), border_color, 4)

        header_text = f"CAM: {self.camera_code}  |  {now.strftime('%d/%m/%Y %H:%M:%S')}  |  FPS: 25.0"
        cv2.putText(frame, header_text, (30, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (220, 220, 220), 2, cv2.LINE_AA)

        rec_color = (0, 0, 255) if pulse == 0 else (60, 60, 180)
        cv2.circle(frame, (w - 200, 38), 9, rec_color, -1)
        cv2.putText(frame, f"REC ({elapsed:.1f}s)", (w - 180, 45), cv2.FONT_HERSHEY_SIMPLEX, 0.7, rec_color, 2, cv2.LINE_AA)

        # Vùng ROI
        roi_pts = np.array([
            [int(w * 0.25), int(h * 0.28)],
            [int(w * 0.75), int(h * 0.28)],
            [int(w * 0.75), int(h * 0.82)],
            [int(w * 0.25), int(h * 0.82)]
        ], np.int32)
        overlay = frame.copy()
        cv2.fillPoly(overlay, [roi_pts], (0, 0, 80))
        cv2.addWeighted(overlay, 0.4, frame, 0.6, 0, frame)
        cv2.polylines(frame, [roi_pts], isClosed=True, color=(0, 0, 255), thickness=2)
        cv2.putText(frame, "KHU VUC GIAM SAT AN NINH / VUNG ROI CAM", (int(w * 0.26), int(h * 0.32)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 120, 255), 2, cv2.LINE_AA)

        # Bounding box mô phỏng đối tượng vi phạm
        cx = int(w * 0.48 + np.sin(elapsed * 1.5) * 80)
        cy = int(h * 0.52)
        bw, bh = 130, 210
        x1, y1 = cx - bw // 2, cy - bh // 2
        x2, y2 = cx + bw // 2, cy + bh // 2
        cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 0, 255), 2)
        cv2.rectangle(frame, (x1, y1 - 28), (x2 + 70, y1), (0, 0, 220), -1)

        type_label = "XAM NHAP TRAI PHEP"
        if "UNKNOWN" in self.event_type.upper():
            type_label = "DOI TUONG KHONG XAC DINH"
        elif "AFTER_HOUR" in self.event_type.upper() or "INTRUSION" in self.event_type.upper():
            type_label = "SAU GIO HOAT DONG"
        elif "UNAUTHORIZED" in self.event_type.upper():
            type_label = "KHONG DU THAM QUYEN"

        cv2.putText(frame, f"TARGET [{type_label}]", (x1 + 6, y1 - 8),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 255, 255), 2, cv2.LINE_AA)

        # Banner cảnh báo
        banner_h = 65
        cv2.rectangle(frame, (15, h - banner_h - 15), (w - 15, h - 15), (15, 15, 20), -1)
        cv2.rectangle(frame, (15, h - banner_h - 15), (w - 15, h - 15), (0, 0, 255), 2)

        cv2.putText(frame, f"CANH BAO SU CO AN NINH: {type_label}", (35, h - banner_h + 15),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 100, 255), 2, cv2.LINE_AA)
        cv2.putText(frame, f"Camera: {self.camera_code} | Dang ghi hinh den khi Bao ve 'Xac nhan da xem'", (35, h - 25),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1, cv2.LINE_AA)

        return frame

    def _draw_incident_overlay_on_real_frame(self, frame: np.ndarray, elapsed: float, now: datetime) -> np.ndarray:
        w, h = self.target_w, self.target_h
        # Chuẩn hóa về 1280x720 để video không bị quá nặng
        if (frame.shape[1], frame.shape[0]) != (w, h):
            vis_frame = cv2.resize(frame, (w, h))
        else:
            vis_frame = frame.copy()

        pulse = int((time.time() * 3) % 2)
        border_color = (0, 0, 230) if pulse == 0 else (0, 0, 140)
        cv2.rectangle(vis_frame, (6, 6), (w - 6, h - 6), border_color, 4)

        # REC indicator
        rec_color = (0, 0, 255) if pulse == 0 else (60, 60, 180)
        cv2.circle(vis_frame, (w - 200, 38), 9, rec_color, -1)
        cv2.putText(vis_frame, f"REC ({elapsed:.1f}s)", (w - 180, 45),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, rec_color, 2, cv2.LINE_AA)

        # Header camera + time
        header_text = f"CAM: {self.camera_code}  |  {now.strftime('%d/%m/%Y %H:%M:%S')}"
        cv2.putText(vis_frame, header_text, (25, 42), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (240, 240, 240), 2, cv2.LINE_AA)

        # Banner cảnh báo
        banner_h = 50
        cv2.rectangle(vis_frame, (10, h - banner_h - 10), (w - 10, h - 10), (0, 0, 0), -1)
        cv2.rectangle(vis_frame, (10, h - banner_h - 10), (w - 10, h - 10), (0, 0, 255), 2)
        cv2.putText(
            vis_frame,
            f"CANH BAO SU CO AN NINH [{self.event_type}] - Camera: {self.camera_code}",
            (25, h - 25),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            (0, 140, 255),
            2,
            cv2.LINE_AA
        )
        return vis_frame

    def _recording_loop(self):
        """Vòng lặp ngầm ghi video chuẩn 25 FPS từ khi phát hiện tới khi Bảo vệ bấm Xác nhận đã xem"""
        frame_interval = 1.0 / self.fps
        next_frame_time = time.time()
        last_real_frame = None

        while self.is_running:
            now_ts = time.time()
            elapsed = now_ts - self.start_ts
            now_dt = datetime.now()

            # Tự động chốt file nếu quá thời gian tối đa (5 phút) để bảo đảm file luôn toàn vẹn
            if elapsed >= self.max_duration_seconds:
                logger.info(f"⏰ [IncidentRecorder] Camera [{self.camera_code}] chạm ngưỡng {self.max_duration_seconds}s. Tự động chốt file video sự cố.")
                break

            # 1. Lấy frame từ queue nếu có
            real_frame = None
            try:
                real_frame = self.frame_queue.get(timeout=0.04)
            except queue.Empty:
                pass

            # 2. Nếu queue rỗng nhưng có frame_provider từ worker
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
                frame_to_write = self._create_synthetic_incident_frame(elapsed, now_dt)

            if self.writer is not None and frame_to_write is not None:
                self.writer.write(frame_to_write)
                self.frames_written += 1

            # Điều tiết nhịp ghi frame ổn định theo 25 FPS
            next_frame_time += frame_interval
            sleep_time = next_frame_time - time.time()
            if sleep_time > 0:
                time.sleep(sleep_time)
            else:
                next_frame_time = time.time()

        self._close_writer()

    def _close_writer(self):
        """Hoàn tất ghi file và giải phóng VideoWriter với moov atom hợp lệ"""
        if self.writer is not None:
            try:
                self.writer.release()
                if os.path.exists(self.file_path):
                    size_mb = os.path.getsize(self.file_path) / (1024 * 1024)
                    duration_s = self.frames_written / max(self.fps, 1.0)
                    logger.info(
                        f"✅ [IncidentRecorder] ĐÃ CHỐT XONG VIDEO BẰNG CHỨNG SỰ CỐ: {self.file_path} "
                        f"({size_mb:.2f} MB - {self.frames_written} frames - {duration_s:.1f}s)"
                    )
            except Exception as e:
                logger.warning(f"⚠️ [IncidentRecorder] Lỗi khi giải phóng VideoWriter: {e}")
            finally:
                self.writer = None
                self.is_running = False

    def stop(self) -> Dict[str, Any]:
        """Dừng ghi hình sự cố khi Bảo vệ click Xác nhận đã xem"""
        if not self.is_running:
            return self.get_summary()

        self.is_running = False
        if self.worker_thread and self.worker_thread.is_alive():
            self.worker_thread.join(timeout=2.5)
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
    """
    Trình quản lý tập trung toàn bộ các phiên ghi hình sự cố:
    - Quản lý theo CAMERA CODE làm khóa chính: Mỗi camera chỉ có DUY NHẤT 1 phiên ghi hình sự cố tại một thời điểm.
    - Dừng ghi hình linh hoạt theo camera_code, incident_id hoặc dừng tất cả.
    """

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

            # Nếu camera này ĐÃ CÓ phiên ghi hình sự cố đang chạy -> tiếp tục ghi hình vào phiên đó
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
        """Phân phối frame trực tiếp từ camera vào phiên ghi hình sự cố của camera đó"""
        cam_code = camera_code.strip().upper()
        with self._lock:
            rec = self._recordings_by_camera.get(cam_code)
            if rec and rec.is_running:
                rec.write_frame(frame)

    def stop_incident_recording(self, key: str) -> Optional[Dict[str, Any]]:
        """
        Dừng phiên ghi hình sự cố: Hỗ trợ tìm kiếm theo incident_id HOẶC theo camera_code
        """
        clean_key = str(key).strip()
        cam_upper = clean_key.upper()
        target_rec: Optional[IncidentRecorder] = None

        with self._lock:
            # 1. Kiểm tra theo camera_code
            if cam_upper in self._recordings_by_camera:
                target_rec = self._recordings_by_camera.pop(cam_upper, None)
            # 2. Kiểm tra theo incident_id
            elif clean_key in self._recordings_by_id:
                target_rec = self._recordings_by_id.pop(clean_key, None)
                if target_rec:
                    self._recordings_by_camera.pop(target_rec.camera_code, None)

        if target_rec:
            # Đồng bộ xóa các khóa liên quan
            with self._lock:
                to_del = [k for k, v in self._recordings_by_id.items() if v == target_rec]
                for k in to_del:
                    self._recordings_by_id.pop(k, None)
            return target_rec.stop()

        logger.info(f"ℹ️ [IncidentManager] Không tìm thấy phiên ghi hình hoạt động cho khóa [{key}].")
        return None

    def stop_camera_recordings(self, camera_code: str) -> List[Dict[str, Any]]:
        """Dừng tất cả phiên ghi hình sự cố của một camera cụ thể"""
        cam_upper = camera_code.strip().upper()
        res = self.stop_incident_recording(cam_upper)
        return [res] if res else []

    def stop_all_incident_recordings(self) -> List[Dict[str, Any]]:
        """Dừng tất cả các phiên ghi hình sự cố khi Guard click 'Xác nhận đã xem tất cả'"""
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


# Singleton instance toàn hệ thống
incident_recording_manager = IncidentRecordingManager()
