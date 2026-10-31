import os
os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
import cv2
import time
import logging
import threading
from typing import Optional, Dict, Any, List

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
            conn.close()
        except Exception as e:
            logger.warning(f"Không thể tải roi_geometry từ DB cho camera [{camera_code}]: {e}")
        return None

    """
    Worker xử lý ngầm luồng video RTSP từ MediaMTX:
    - Kéo frame liên tục từ RTSP stream theo từng camera
    - Ghi hình liên tục vào ổ đĩa, cắt chuẩn xác 1 phút/session
    - Phân tích qua VideoPipeline (YOLO11 + YuNet)
    - Gửi Incident Event sang MQTT Broker khi phát hiện vi phạm
    """
    def __init__(
        self,
        camera_code: str,
        rtsp_url: str,
        roi_geometry: Optional[Dict[str, Any]] = None
    ):
        if not camera_code or not camera_code.strip():
            raise ValueError("camera_code is required and cannot be empty")
        if not rtsp_url or not rtsp_url.strip():
            raise ValueError("rtsp_url is required and cannot be empty")

        self.camera_code = camera_code.strip()
        self.rtsp_url = rtsp_url.strip()
        self.pipeline = VideoPipeline(camera_code=self.camera_code)
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
        self.raw_frame_lock = threading.Lock()
        self.latest_frame = None
        self.processed_fps = 0.0
        self.lock = threading.Lock()

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

    def update_runtime_config(self, max_inactive_seconds: Optional[float] = None, unknown_alert_cooldown: Optional[float] = None):
        """Cập nhật cấu hình runtime tracking và cooldown cho worker đang chạy"""
        if self.pipeline and self.pipeline.analysis_engine:
            if max_inactive_seconds is not None:
                self.pipeline.analysis_engine.max_inactive_seconds = max_inactive_seconds
            if unknown_alert_cooldown is not None:
                self.pipeline.analysis_engine.unknown_alert_cooldown = unknown_alert_cooldown
            logger.info(f"Đã cập nhật runtime config cho Stream Worker [{self.camera_code}]: max_inactive={self.pipeline.analysis_engine.max_inactive_seconds}s, cooldown={self.pipeline.analysis_engine.unknown_alert_cooldown}s")

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
        logger.info(f"Đã khởi động Stream Worker (Threaded Grabber & Continuous Recorder) cho Camera [{self.camera_code}] từ: {self.rtsp_url}")

    def stop(self):
        """Dừng worker và hoàn tất ghi file video"""
        self.is_running = False
        if self.capture_thread:
            self.capture_thread.join(timeout=2.0)
            self.capture_thread = None
        if self.analysis_thread:
            self.analysis_thread.join(timeout=2.0)
            self.analysis_thread = None
        if self.recorder:
            self.recorder.stop()
        logger.info(f"Đã dừng Stream Worker cho Camera [{self.camera_code}].")

    def _capture_loop(self):
        """Luồng chuyên biệt kéo frame RTSP liên tục và ghi hình vào đĩa (zero lag)"""
        logger.info(f"Khởi động Capture Thread cho camera [{self.camera_code}]: {self.rtsp_url}")
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        fail_count = 0

        while self.is_running:
            ret, frame = cap.read()
            if not ret or frame is None:
                fail_count += 1
                time.sleep(0.3)
                if fail_count >= 10:
                    logger.warning(f"RTSP stream [{self.camera_code}] gián đoạn, đang kết nối lại VideoCapture...")
                    cap.release()
                    time.sleep(1.0)
                    cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
                    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                    fail_count = 0
                continue
            fail_count = 0

            # Ghi hình liên tục vào đĩa (Non-blocking qua hàng đợi)
            if self.recorder:
                self.recorder.write_frame(frame)

            # Ghi hình sự cố an ninh nếu có phiên ghi hình sự cố đang chạy cho camera này
            incident_recording_manager.write_frame_for_camera(self.camera_code, frame)

            with self.raw_frame_lock:
                self.raw_frame = frame

        if self.recorder:
            self.recorder.stop()
        cap.release()
        logger.info(f"Đã đóng Capture Thread cho camera [{self.camera_code}].")

    def _analysis_loop(self):
        """Luồng phân tích AI: Luôn lấy frame mới nhất (Fresh Frame) để xử lý"""
        logger.info(f"Khởi động Analysis Thread cho camera [{self.camera_code}]")
        fps_timer = time.time()
        frame_counter = 0

        while self.is_running:
            with self.raw_frame_lock:
                frame = self.raw_frame.copy() if self.raw_frame is not None else None

            if frame is None:
                time.sleep(0.02)
                continue

            try:
                annotated_frame, tracked_persons, alerts = self.pipeline.process_frame(frame)
                with self.lock:
                    self.latest_frame = annotated_frame

                frame_counter += 1
                if time.time() - fps_timer >= 2.0:
                    self.processed_fps = round(frame_counter / (time.time() - fps_timer), 1)
                    fps_timer = time.time()
                    frame_counter = 0

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
