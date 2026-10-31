import sys
import os
import time
import unittest
import numpy as np
import cv2

# Ensure ai-service root is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

# Set mock env variables for config
os.environ["MINIO_ROOT_USER"] = "testuser"
os.environ["MINIO_ROOT_PASSWORD"] = "testpassword123"
os.environ["INTERNAL_SERVICE_KEY"] = "12345678901234567890123456789012"

from src.core.entity import Point, RoiPolygonConfig
from src.core.zone_state_machine import ZoneState
from src.pipeline.video_pipeline import VideoPipeline

class TestMotionGatedPipeline(unittest.TestCase):
    def setUp(self):
        self.pipeline = VideoPipeline(
            camera_code="CAM-TEST",
            enable_motion_gating=True
        )
        # ROI in center (0.25..0.75, 0.25..0.75)
        self.roi = RoiPolygonConfig(
            label="Khu-vuc-cam-Server",
            vertices=[
                Point(0.25, 0.25),
                Point(0.75, 0.25),
                Point(0.75, 0.75),
                Point(0.25, 0.75)
            ]
        )
        self.pipeline.set_roi_config([self.roi])
        self.width = 640
        self.height = 480

    def test_idle_fast_skip_deep_learning(self):
        """Khung hình tĩnh trong phòng vắng: Bỏ qua 100% suy luận Deep Learning, xử lý siêu tốc"""
        static_bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)

        # Warm up background
        for _ in range(5):
            self.pipeline.process_frame(static_bg)

        # State should be IDLE
        self.assertEqual(self.pipeline.state_machine.state, ZoneState.IDLE)

        # Measure processing time of idle frame
        start_t = time.time()
        _, active_persons, alerts = self.pipeline.process_frame(static_bg)
        elapsed_ms = (time.time() - start_t) * 1000.0

        # Should be extremely fast (< 10ms) because YOLO/Face is skipped
        self.assertEqual(len(active_persons), 0)
        self.assertEqual(len(alerts), 0)
        self.assertLess(elapsed_ms, 25.0)

    def test_camouflage_intrusion_detection(self):
        """Kẻ ngụy trang (trùm chăn, chui thùng, cosplay): Có chuyển động lớn nhưng không ra dáng người -> Kích hoạt SUSPICIOUS_MOTION"""
        bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)
        for _ in range(5):
            self.pipeline.process_frame(bg)

        # Tạo khối chuyển động lớn không phải người (ví dụ: khối hộp trắng di chuyển ở giữa ROI)
        motion_frame = bg.copy()
        cv2.rectangle(motion_frame, (260, 200), (380, 320), (220, 220, 220), -1)

        _, active_persons, alerts = self.pipeline.process_frame(motion_frame)

        # Kiểm tra sự cố SUSPICIOUS_MOTION được phát sinh
        event_types = [a.event_type for a in alerts]
        self.assertIn("SUSPICIOUS_MOTION", event_types)
        print("✅ TEST PASSED: Kẻ ngụy trang bị tóm gọn bởi sự cố SUSPICIOUS_MOTION!")

    def test_camera_tampering_blinding_laser(self):
        """Kẻ trộm rọi đèn laser / làm mù camera kéo dài -> Kích hoạt CAMERA_TAMPERING"""
        bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)
        for _ in range(5):
            self.pipeline.process_frame(bg)

        # Đốm trắng cháy sáng tâm ống kính
        tamper_frame = bg.copy()
        cv2.circle(tamper_frame, (320, 240), 90, (255, 255, 255), -1)

        # First frame: starts timer
        self.pipeline.process_frame(tamper_frame)

        # After 1.6s of persistent blinding
        time.sleep(1.6)
        _, _, alerts = self.pipeline.process_frame(tamper_frame)

        event_types = [a.event_type for a in alerts]
        self.assertIn("CAMERA_TAMPERING", event_types)
        print("✅ TEST PASSED: Hành vi phá hoại chiếu đèn bị phát hiện bởi CAMERA_TAMPERING!")

if __name__ == "__main__":
    unittest.main()
