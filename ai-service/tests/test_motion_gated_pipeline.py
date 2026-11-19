import sys
import os
import time
import unittest
import pytest
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

@pytest.mark.requires_model
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
        self.assertLess(elapsed_ms, 120.0)

    def test_camouflage_intrusion_detection(self):
        """Kẻ ngụy trang (trùm chăn, chui thùng, cosplay): Có chuyển động lớn nhưng không ra dáng người -> Kích hoạt SUSPICIOUS_MOTION"""
        bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)
        for _ in range(5):
            self.pipeline.process_frame(bg)

        # Tạo khối chuyển động lớn không phải người (ví dụ: khối hộp trắng di chuyển ở giữa ROI)
        motion_frame1 = bg.copy()
        cv2.rectangle(motion_frame1, (240, 200), (360, 320), (220, 220, 220), -1)

        motion_frame2 = bg.copy()
        cv2.rectangle(motion_frame2, (280, 200), (400, 320), (220, 220, 220), -1)

        t0 = time.time()
        # Khung hình đầu tiên: chuyển động mới xuất hiện (< 0.5s) -> Chưa cảnh báo (chống nhiễu tức thời)
        _, _, alerts_0 = self.pipeline.process_frame(motion_frame1, current_time=t0)
        self.assertNotIn("SUSPICIOUS_MOVING_OBJECT", [a.event_type for a in alerts_0])

        # Sau >= 0.5s chuyển động liên tục trong ROI -> Kích hoạt cảnh báo SUSPICIOUS_MOTION
        _, active_persons, alerts = self.pipeline.process_frame(motion_frame2, current_time=t0 + 0.6)

        # Kiểm tra sự cố SUSPICIOUS_MOTION được phát sinh
        event_types = [a.event_type for a in alerts]
        self.assertIn("SUSPICIOUS_MOVING_OBJECT", event_types)
        print("✅ TEST PASSED: Kẻ ngụy trang bị tóm gọn bởi sự cố SUSPICIOUS_MOTION!")

    def test_stop_and_go_camouflage_within_gap_tolerance_still_alerts(self):
        """Kẻ ngụy trang di chuyển ngắt quãng / sụt viền ROI trong khoảng dung sai <= 0.6s vẫn bị phát hiện"""
        bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)
        for _ in range(5):
            self.pipeline.process_frame(bg)

        motion_frame1 = bg.copy()
        cv2.rectangle(motion_frame1, (240, 200), (360, 320), (220, 220, 220), -1)

        motion_frame2 = bg.copy()
        cv2.rectangle(motion_frame2, (280, 200), (400, 320), (220, 220, 220), -1)

        t0 = 1000.0
        # Frame 1: Bắt đầu chuyển động (t=1000.0, thời lượng 0s < 0.5s) -> Chưa cảnh báo
        _, _, alerts_0 = self.pipeline.process_frame(motion_frame1, current_time=t0)
        self.assertNotIn("SUSPICIOUS_MOVING_OBJECT", [a.event_type for a in alerts_0])
        self.assertEqual(self.pipeline._suspicious_motion_start_time, t0)

        # Frame 2: Kẻ gian dừng lại / sụt viền ở t=1000.2 (gap 0.2s <= gap_tolerance 0.6s) -> Không reset timer
        _, _, alerts_1 = self.pipeline.process_frame(bg, current_time=t0 + 0.2)
        self.assertNotIn("SUSPICIOUS_MOVING_OBJECT", [a.event_type for a in alerts_1])
        self.assertEqual(self.pipeline._suspicious_motion_start_time, t0)

        # Frame 3: Nhích tiếp ở t=1000.55 (gap 0.35s <= 0.6s, tổng thời gian 0.55s >= 0.5s) -> Kích hoạt cảnh báo!
        _, _, alerts_2 = self.pipeline.process_frame(motion_frame2, current_time=t0 + 0.55)
        self.assertIn("SUSPICIOUS_MOVING_OBJECT", [a.event_type for a in alerts_2])

    def test_intermittent_noise_exceeding_gap_tolerance_resets_and_does_not_alert(self):
        """Nhiễu rời rạc cách xa nhau > 0.6s sẽ bị reset bộ đếm, không bao giờ cộng dồn nhầm thành cảnh báo"""
        bg = np.full((self.height, self.width, 3), 40, dtype=np.uint8)
        for _ in range(5):
            self.pipeline.process_frame(bg)

        motion_frame1 = bg.copy()
        cv2.rectangle(motion_frame1, (240, 200), (360, 320), (220, 220, 220), -1)

        t0 = 2000.0
        # Nhiễu thoáng qua lần 1 ở t=2000.0
        self.pipeline.process_frame(motion_frame1, current_time=t0)
        self.assertEqual(self.pipeline._suspicious_motion_start_time, t0)

        # Khoảng lặng kéo dài 0.8s (> 0.6s gap tolerance) -> Reset timer
        self.pipeline.process_frame(bg, current_time=t0 + 0.8)
        self.assertIsNone(self.pipeline._suspicious_motion_start_time)
        self.assertIsNone(self.pipeline._last_suspicious_candidate_time)

        # Nhiễu thoáng qua lần 2 ở t=2001.0 -> Được tính là chuỗi mới, chưa đủ 0.5s -> Không cảnh báo
        _, _, alerts = self.pipeline.process_frame(motion_frame1, current_time=t0 + 1.0)
        self.assertNotIn("SUSPICIOUS_MOVING_OBJECT", [a.event_type for a in alerts])

if __name__ == "__main__":
    unittest.main()
