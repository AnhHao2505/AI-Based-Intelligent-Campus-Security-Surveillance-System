import cv2
import time
import logging
import threading
from typing import Optional, Dict, Any

from .video_pipeline import VideoPipeline
from ..config import settings

logger = logging.getLogger(__name__)

class CameraStreamWorker:
    """
    Worker xử lý ngầm luồng video RTSP từ MediaMTX:
    - Kéo frame liên tục từ rtsp://.../cam01
    - Phân tích qua VideoPipeline (YOLOv8 + YuNet + Loitering)
    - Gửi Incident Event sang Kafka khi phát hiện vi phạm
    """
    def __init__(self, camera_code: str = "CAM-001", rtsp_url: str = "rtsp://localhost:8554/cam01"):
        self.camera_code = camera_code
        self.rtsp_url = rtsp_url
        self.pipeline = VideoPipeline(camera_code=camera_code)
        
        self.is_running = False
        self.thread: Optional[threading.Thread] = None
        self.latest_frame = None
        self.processed_fps = 0.0
        self.lock = threading.Lock()

    def start(self):
        """Bắt đầu worker đọc luồng trong luồng riêng (Thread)"""
        if self.is_running:
            logger.info(f"Worker camera [{self.camera_code}] đang chạy rồi.")
            return

        self.is_running = True
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()
        logger.info(f"Đã khởi động Stream Worker cho Camera [{self.camera_code}] từ: {self.rtsp_url}")

    def stop(self):
        """Dừng worker"""
        self.is_running = False
        if self.thread:
            self.thread.join(timeout=3.0)
            self.thread = None
        logger.info(f"Đã dừng Stream Worker cho Camera [{self.camera_code}].")

    def _run_loop(self):
        """Vòng lặp đọc frame và phân tích AI"""
        logger.info(f"Bắt đầu kết nối luồng RTSP: {self.rtsp_url}")
        
        # Ép OpenCV sử dụng giao thức TCP để tránh rớt gói tin H.264 qua Wi-Fi
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        fps_timer = time.time()
        frame_counter = 0

        while self.is_running:
            ret, frame = cap.read()
            if not ret or frame is None:
                # Đang chờ nguồn phát từ MediaMTX / Điện thoại
                time.sleep(1.0)
                if not cap.isOpened():
                    cap.open(self.rtsp_url)
                continue

            # Phân tích frame qua VideoPipeline
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
                time.sleep(0.1)

        cap.release()
        logger.info(f"Đã giải phóng luồng VideoCapture cho camera [{self.camera_code}].")

    def get_status(self) -> Dict[str, Any]:
        return {
            "camera_code": self.camera_code,
            "rtsp_url": self.rtsp_url,
            "is_running": self.is_running,
            "processed_fps": self.processed_fps,
            "active_tracks_count": len(self.pipeline.loitering_engine.active_tracks) if self.pipeline else 0
        }
