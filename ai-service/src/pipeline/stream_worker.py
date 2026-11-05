from ..utils.security import mask_rtsp_url
import os
os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
import cv2
import time
import logging
import threading
from typing import Optional, Dict, Any, List, Iterable

def select_stream_path(camera_code: str, available_paths: Optional[Iterable[str]] = None) -> str:
    """
    Chọn luồng MediaMTX tối ưu cho AI & Recording:
    - Nếu có luồng chính ({camera_code}_main) trong danh sách available_paths -> chọn {camera_code}_main.
    - Ngược lại -> chọn {camera_code}.
    """
    if not camera_code:
        return ""
    code = camera_code.strip()
    if not available_paths:
        return code

    paths_set = set(p.strip() for p in available_paths if p)
    main_variant = f"{code}_main"
    for p in paths_set:
        if p.lower() == main_variant.lower():
            return p
    return code

def resolve_camera_rtsp_url(
    camera_code: str,
    base_rtsp_gateway: Optional[str] = None,
    available_paths: Optional[Iterable[str]] = None
) -> str:
    """
    Tạo RTSP URL gateway cho camera, tự động ưu tiên {camera_code}_main nếu có trong MediaMTX.
    Mặc định sử dụng settings.MEDIAMTX_RTSP_URL.
    """
    stream_name = select_stream_path(camera_code, available_paths)
    effective_base = base_rtsp_gateway or getattr(settings, "MEDIAMTX_RTSP_URL", "rtsp://localhost:8554")
    base = effective_base.rstrip("/")
    return f"{base}/{stream_name}"

from .video_pipeline import VideoPipeline
from .stream_recorder import StreamRecorder
from .incident_recorder import incident_recording_manager
from ..config import settings

logger = logging.getLogger(__name__)

class CameraStreamWorker:
    @staticmethod
    def _fetch_roi_geometry(camera_code: str) -> Optional[Dict[str, Any]]:
        import psycopg2
        import json
        from ..config import settings
        conn = None
        try:
            conn = psycopg2.connect(
                host=settings.DB_HOST,
                port=settings.DB_PORT,
                user=settings.POSTGRES_USER,
                password=settings.POSTGRES_PASSWORD,
                dbname=settings.POSTGRES_DB,
                connect_timeout=3
            )
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT roi_geometry FROM cameras WHERE camera_code = %s AND deleted_at IS NULL",
                    (camera_code,)
                )
                row = cur.fetchone()
                if row and row[0]:
                    geom = row[0]
                    if isinstance(geom, str):
                        geom = json.loads(geom)
                    return geom
        except Exception as e:
            logger.warning(f"Không thể tải roi_geometry từ DB cho camera [{camera_code}]: {e}")
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
        return None

    """
    Worker xử lý ngầm luồng video RTSP từ MediaMTX:
    - Kéo frame liên tục từ RTSP stream theo từng camera
    - Ghi hình liên tục vào ổ đĩa, cắt chuẩn xác 1 phút/session
    - Phân tích qua VideoPipeline (YOLO26 + YuNet)
    - Gửi Incident Event sang MQTT Broker khi phát hiện vi phạm
    """
    def __init__(
        self,
        camera_code: str,
        rtsp_url: str,
        roi_geometry: Optional[Dict[str, Any]] = None,
        analysis_max_fps: Optional[float] = None
    ):
        if not camera_code or not camera_code.strip():
            raise ValueError("camera_code is required and cannot be empty")
        if not rtsp_url or not rtsp_url.strip():
            raise ValueError("rtsp_url is required and cannot be empty")

        self.camera_code = camera_code.strip()
        self.rtsp_url = rtsp_url.strip()
        self.analysis_max_fps = analysis_max_fps if analysis_max_fps is not None else getattr(settings, "ANALYSIS_MAX_FPS", 10.0)
        self.pipeline = VideoPipeline(camera_code=self.camera_code, analysis_max_fps=self.analysis_max_fps)
        if not roi_geometry:
            roi_geometry = self._fetch_roi_geometry(self.camera_code)

        if roi_geometry:
            polys = roi_geometry.get("polygons", [])
            self.pipeline.set_roi_config(polygons=polys)
            logger.info(f"Stream Worker camera [{self.camera_code}] đã tải {len(polys)} ROI polygons từ DB.")
        
        # Recorder tự động ghi hình toàn bộ luồng camera theo từng phân đoạn 1 phút tại C:\DATN\...\recording
        self.recorder = StreamRecorder(
            camera_code=self.camera_code,
            base_dir=settings.RECORDING_BASE_DIR,
            segment_seconds=settings.RECORDING_SEGMENT_SECONDS
        )

        self.is_running = False
        self.capture_thread: Optional[threading.Thread] = None
        self.analysis_thread: Optional[threading.Thread] = None
        self.raw_frame = None
        self.frame_id: int = 0
        self.raw_frame_lock = threading.Lock()
        self.latest_frame = None
        self.processed_fps = 0.0
        self.lock = threading.Lock()
        self._active_clients_count: int = 0
        self._clients_lock = threading.Lock()

    def add_client(self):
        """Đăng ký client đang xem live-annotated stream (P1-05 Lazy Overlay)"""
        with self._clients_lock:
            self._active_clients_count += 1

    def remove_client(self):
        """Hủy đăng ký client xem live-annotated stream (P1-05 Lazy Overlay)"""
        with self._clients_lock:
            self._active_clients_count = max(0, self._active_clients_count - 1)

    @property
    def has_active_client(self) -> bool:
        """Có ít nhất 1 client đang xem stream qua web/client (P1-05 Lazy Overlay)"""
        with self._clients_lock:
            return self._active_clients_count > 0

    def update_roi(self, polygons: Optional[List[Any]] = None,
                   after_hour_start: Optional[str] = None, after_hour_end: Optional[str] = None):
        """Cập nhật cấu hình ROI cho worker đang chạy"""
        if self.pipeline:
            self.pipeline.set_roi_config(polygons=polygons)
            if after_hour_start:
                self.pipeline.analysis_engine.after_hour_start = after_hour_start
            if after_hour_end:
                self.pipeline.analysis_engine.after_hour_end = after_hour_end
            poly_count = len(polygons) if polygons else 0
            logger.info(f"Đã cập nhật ROI ({poly_count} polygons) cho Stream Worker [{self.camera_code}].")

    def update_runtime_config(
        self,
        max_inactive_seconds: Optional[float] = None,
        unknown_alert_cooldown: Optional[float] = None,
        analysis_max_fps: Optional[float] = None
    ):
        """Cập nhật cấu hình runtime tracking, cooldown và analysis FPS cho worker đang chạy (P0-03)"""
        if self.pipeline and self.pipeline.analysis_engine:
            if max_inactive_seconds is not None:
                self.pipeline.analysis_engine.max_inactive_seconds = max_inactive_seconds
            if unknown_alert_cooldown is not None:
                self.pipeline.analysis_engine.unknown_alert_cooldown = unknown_alert_cooldown
        if analysis_max_fps is not None and analysis_max_fps > 0:
            self.analysis_max_fps = analysis_max_fps
            if self.pipeline and hasattr(self.pipeline, "set_analysis_max_fps"):
                self.pipeline.set_analysis_max_fps(analysis_max_fps)
        logger.info(f"Đã cập nhật runtime config cho Stream Worker [{self.camera_code}]: analysis_fps={self.analysis_max_fps}")

    def start(self):
        """Bắt đầu worker đọc luồng, ghi hình và phân tích AI trên các luồng độc lập"""
        if self.is_running:
            logger.info(f"Worker camera [{self.camera_code}] đang chạy rồi.")
            return

        self.is_running = True
        if self.recorder:
            self.recorder.start()
        self.capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self.capture_thread.start()
        self.analysis_thread = threading.Thread(target=self._analysis_loop, daemon=True)
        self.analysis_thread.start()
        logger.info(f"Đã khởi động Stream Worker (Threaded Grabber & Continuous Recorder) cho Camera [{self.camera_code}] từ: {mask_rtsp_url(self.rtsp_url)}")

    def stop(self):
        """Dừng worker và hoàn tất ghi file video"""
        self.is_running = False
        if hasattr(self, "cap") and self.cap is not None:
            try:
                self.cap.release()
            except Exception:
                pass
        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=2.0)
            self.capture_thread = None
        if self.analysis_thread and self.analysis_thread.is_alive():
            self.analysis_thread.join(timeout=2.0)
            self.analysis_thread = None
        if self.recorder:
            self.recorder.stop()
        if hasattr(self, "pipeline") and self.pipeline is not None:
            try:
                self.pipeline.stop()
            except Exception as ex:
                logger.warning(f"Lỗi khi dừng pipeline trong CameraStreamWorker: {ex}")
        logger.info(f"Đã dừng Stream Worker cho Camera [{self.camera_code}].")

    def _capture_loop(self):
        """Luồng chuyên biệt kéo frame RTSP liên tục và ghi hình vào đĩa (zero lag)"""
        logger.info(f"Khởi động Capture Thread cho camera [{self.camera_code}]: {mask_rtsp_url(self.rtsp_url)}")
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp|stimeout;5000000"
        cap = None
        fail_count = 0

        try:
            cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
            cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000)
            cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            self.cap = cap

            while self.is_running:
                try:
                    ret, frame = cap.read()
                except Exception as ex_read:
                    logger.warning(f"Lỗi đọc frame camera [{self.camera_code}]: {ex_read}")
                    ret, frame = False, None

                if not ret or frame is None:
                    fail_count += 1
                    time.sleep(0.3)
                    if fail_count >= 10:
                        logger.warning(
                            f"⚠️ [StreamWorker] Camera [{self.camera_code}] chưa có luồng từ MediaMTX ({mask_rtsp_url(self.rtsp_url)}). "
                            f"Kiểm tra xem camera/điện thoại phát RTSP đã bật và đúng địa chỉ IP chưa. Đang thử kết nối lại sau 3s..."
                        )
                        try:
                            cap.release()
                        except Exception:
                            pass
                        time.sleep(3.0)
                        if not self.is_running:
                            break
                        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
                        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000)
                        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
                        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                        self.cap = cap
                        fail_count = 0
                    continue
                fail_count = 0

                # Ghi hình liên tục vào đĩa (Non-blocking qua hàng đợi)
                if self.recorder:
                    self.recorder.write_frame(frame)

                with self.raw_frame_lock:
                    self.raw_frame = frame
                    self.frame_id += 1
        except Exception as e:
            logger.error(f"Lỗi ngoại lệ trong capture thread camera [{self.camera_code}]: {e}")
        finally:
            if cap is not None:
                try:
                    cap.release()
                except Exception:
                    pass
            self.cap = None
            if self.recorder:
                self.recorder.stop()
            logger.info(f"Đã đóng Capture Thread cho camera [{self.camera_code}].")

    def _analysis_loop(self):
        """Luồng phân tích AI: Luôn lấy frame mới nhất (Fresh Frame) để xử lý theo chu kỳ ANALYSIS_MAX_FPS (P0-03)"""
        logger.info(f"Khởi động Analysis Thread cho camera [{self.camera_code}] (target FPS: {self.analysis_max_fps})")
        fps_timer = time.time()
        frame_counter = 0

        last_processed_frame_id = -1
        last_analysis_time = 0.0

        while self.is_running:
            min_analysis_interval = (1.0 / self.analysis_max_fps) if (self.analysis_max_fps and self.analysis_max_fps > 0) else 0.0
            if min_analysis_interval > 0:
                now = time.time()
                elapsed = now - last_analysis_time
                if elapsed < min_analysis_interval:
                    sleep_dur = min(0.05, max(0.001, min_analysis_interval - elapsed))
                    time.sleep(sleep_dur)
                    continue

            with self.raw_frame_lock:
                if self.raw_frame is not None and self.frame_id != last_processed_frame_id:
                    frame = self.raw_frame.copy()
                    last_processed_frame_id = self.frame_id
                else:
                    frame = None

            if frame is None:
                time.sleep(0.01)
                continue

            last_analysis_time = time.time()
            try:
                need_annotated = self.has_active_client or incident_recording_manager.is_recording(self.camera_code)
                annotated_frame, tracked_persons, alerts = self.pipeline.process_frame(
                    frame,
                    need_annotated=need_annotated
                )
                with self.lock:
                    self.latest_frame = annotated_frame
                if need_annotated or alerts:
                    incident_recording_manager.write_frame_for_camera(self.camera_code, annotated_frame)

                frame_counter += 1
                if time.time() - fps_timer >= 2.0:
                    self.processed_fps = round(frame_counter / (time.time() - fps_timer), 1)
                    fps_timer = time.time()
                    frame_counter = 0
                    active_cnt = len(self.pipeline.analysis_engine.active_tracks) if self.pipeline else 0
                    state_val = self.pipeline.state_machine.state.value if (self.pipeline and hasattr(self.pipeline, "state_machine")) else "N/A"
                    logger.info(
                        f"📊 [StreamWorker:{self.camera_code}] Trạng thái: {self.processed_fps} FPS | Vùng: {state_val} | Người đang theo dõi: {active_cnt}"
                    )

            except Exception as e:
                logger.error(f"Lỗi phân tích frame camera [{self.camera_code}]: {e}")
                time.sleep(0.05)

        logger.info(f"Đã đóng Analysis Thread cho camera [{self.camera_code}].")

    def get_current_raw_frame(self) -> Optional[Any]:
        with self.raw_frame_lock:
            return self.raw_frame.copy() if self.raw_frame is not None else None

    def get_status(self) -> Dict[str, Any]:
        return {
            "camera_code": self.camera_code,
            "rtsp_url": self.rtsp_url,
            "is_running": self.is_running,
            "processed_fps": self.processed_fps,
            "active_tracks_count": len(self.pipeline.analysis_engine.active_tracks) if self.pipeline else 0
        }
