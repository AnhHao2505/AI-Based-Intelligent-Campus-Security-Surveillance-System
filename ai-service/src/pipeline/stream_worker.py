import os
os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
import cv2
import time
import logging
import threading
from typing import Optional, Dict, Any, List

from .video_pipeline import VideoPipeline
from ..config import settings

logger = logging.getLogger(__name__)

class CameraStreamWorker:
    """
    Worker xử lý ngầm luồng video RTSP từ MediaMTX:
    - Kéo frame liên tục từ RTSP stream theo từng camera
    - Phân tích qua VideoPipeline (YOLOv8 + YuNet)
    - Gửi Incident Event sang Kafka khi phát hiện vi phạm
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
        if roi_geometry and "polygons" in roi_geometry:
            self.pipeline.set_roi_config(roi_geometry["polygons"])
        
        self.is_running = False
        self.thread: Optional[threading.Thread] = None
        self.latest_frame = None
        self.processed_fps = 0.0
        self.lock = threading.Lock()

    def update_roi(self, polygons: List[Any],
                   after_hour_start: Optional[str] = None, after_hour_end: Optional[str] = None):
        """Cập nhật cấu hình ROI cho worker đang chạy"""
        if self.pipeline:
            self.pipeline.set_roi_config(polygons)
            if after_hour_start:
                self.pipeline.analysis_engine.after_hour_start = after_hour_start
            if after_hour_end:
                self.pipeline.analysis_engine.after_hour_end = after_hour_end
            logger.info(f"Đã cập nhật ROI ({len(polygons)} polygons) cho Stream Worker [{self.camera_code}].")

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
        
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        fps_timer = time.time()
        frame_counter = 0

        while self.is_running:
            ret, frame = cap.read()
            if not ret or frame is None:
                time.sleep(1.0)
                if not cap.isOpened():
                    cap.open(self.rtsp_url)
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
                time.sleep(0.1)

        cap.release()
        logger.info(f"Đã giải phóng luồng VideoCapture cho camera [{self.camera_code}].")

    def get_status(self) -> Dict[str, Any]:
        return {
            "camera_code": self.camera_code,
            "rtsp_url": self.rtsp_url,
            "is_running": self.is_running,
            "processed_fps": self.processed_fps,
            "active_tracks_count": len(self.pipeline.analysis_engine.active_tracks) if self.pipeline else 0
        }
