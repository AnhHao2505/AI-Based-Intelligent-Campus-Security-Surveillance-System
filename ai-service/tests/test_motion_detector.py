import sys
import os
import time
import unittest
import numpy as np
import cv2

# Ensure ai-service root is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import Point, RoiPolygonConfig, MotionResult
from src.core.motion_detector import MotionDetector

class TestMotionDetector(unittest.TestCase):
    def setUp(self):
        # 640x480 frame for testing
        self.width = 640
        self.height = 480
        self.detector = MotionDetector(
            history=100,
            var_threshold=25,
            min_motion_area=2000,
            tampering_duration_threshold=1.0
        )
        # ROI in center (0.25..0.75, 0.25..0.75)
        self.roi = RoiPolygonConfig(
            label="Test-Zone",
            vertices=[
                Point(0.25, 0.25),
                Point(0.75, 0.25),
                Point(0.75, 0.75),
                Point(0.25, 0.75)
            ]
        )

    def test_static_scene_no_motion(self):
        """Khung hình tĩnh 100% không phát sinh chuyển động"""
        static_frame = np.full((self.height, self.width, 3), 50, dtype=np.uint8)
        
        # Warm up background
        for _ in range(10):
            res = self.detector.detect(static_frame, [self.roi])
            
        self.assertFalse(res.has_motion)
        self.assertEqual(res.motion_area, 0)
        self.assertFalse(res.is_tampering)
        self.assertFalse(res.is_lighting_spike)

    def test_moving_object_in_roi(self):
        """Vật thể chuyển động lớn bên trong ROI phải được phát hiện"""
        bg = np.full((self.height, self.width, 3), 30, dtype=np.uint8)
        # Warm up
        for _ in range(10):
            self.detector.detect(bg, [self.roi])

        # Object (80x80 = 6400px > 2000px) moving in center of ROI
        frame_with_obj = bg.copy()
        # Center is (x: 280..360, y: 200..280) which is inside [160..480, 120..360]
        cv2.rectangle(frame_with_obj, (280, 200), (360, 280), (220, 220, 220), -1)
        
        res = self.detector.detect(frame_with_obj, [self.roi])
        self.assertTrue(res.has_motion)
        self.assertGreater(res.motion_area, 1000)
        self.assertGreater(len(res.motion_boxes), 0)

    def test_moving_object_outside_roi_ignored(self):
        """Vật thể chuyển động bên ngoài ROI phải bị triệt tiêu hoàn toàn"""
        bg = np.full((self.height, self.width, 3), 30, dtype=np.uint8)
        for _ in range(10):
            self.detector.detect(bg, [self.roi])

        # Moving object strictly at top-left corner (x: 10..90, y: 10..90) outside ROI
        frame_outside = bg.copy()
        cv2.rectangle(frame_outside, (10, 10), (90, 90), (220, 220, 220), -1)

        res = self.detector.detect(frame_outside, [self.roi])
        self.assertFalse(res.has_motion)
        self.assertEqual(len(res.motion_boxes), 0)

    def test_small_noise_filtered(self):
        """Nhiễu hạt nhỏ / côn trùng bay phải bị lọc bởi Morphological Opening"""
        bg = np.full((self.height, self.width, 3), 30, dtype=np.uint8)
        for _ in range(10):
            self.detector.detect(bg, [self.roi])

        # Tiny dot 10x10 = 100px (< 2000px threshold)
        frame_tiny = bg.copy()
        cv2.rectangle(frame_tiny, (300, 240), (310, 250), (200, 200, 200), -1)

        res = self.detector.detect(frame_tiny, [self.roi])
        self.assertFalse(res.has_motion)

    def test_global_lighting_spike(self):
        """Đổi sáng đột ngột (>70% diện tích ROI) kích hoạt cờ is_lighting_spike"""
        bg = np.full((self.height, self.width, 3), 30, dtype=np.uint8)
        for _ in range(10):
            self.detector.detect(bg, [self.roi])

        # Sudden bright frame (simulate light turned on)
        bright_frame = np.full((self.height, self.width, 3), 180, dtype=np.uint8)
        res = self.detector.detect(bright_frame, [self.roi])
        self.assertTrue(res.is_lighting_spike)

    def test_tampering_sensor_blinded(self):
        """Chiếu đèn laser / đèn pin bão hòa trắng kéo dài kích hoạt CAMERA_TAMPERING"""
        bg = np.full((self.height, self.width, 3), 30, dtype=np.uint8)
        for _ in range(5):
            self.detector.detect(bg, [self.roi])

        # Saturated white circle at center (simulate laser/flashlight beam directly into lens)
        tamper_frame = bg.copy()
        cv2.circle(tamper_frame, (320, 240), 90, (255, 255, 255), -1)

        # First frame: tampering not yet triggered (requires duration > threshold)
        res1 = self.detector.detect(tamper_frame, [self.roi])
        self.assertFalse(res1.is_tampering)

        # Simulate elapsed time > 1.0s
        time.sleep(1.1)
        res2 = self.detector.detect(tamper_frame, [self.roi])
        self.assertTrue(res2.is_tampering)

if __name__ == "__main__":
    unittest.main()
