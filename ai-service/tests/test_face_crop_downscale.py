"""Crop rất lớn (camera 2688x1664) được thu nhỏ trước YuNet; toạ độ mặt quy đổi đúng về khung hình gốc."""
import unittest
from unittest.mock import MagicMock, patch

import numpy as np

from src.core.entity import BoundingBox, FaceDetectionResult
from src.core.face_detector import FaceDetector


class TestFaceCropDownscale(unittest.TestCase):
    @patch("os.path.exists", return_value=True)
    @patch("cv2.FaceDetectorYN.create")
    def setUp(self, mock_yn_create, mock_exists):
        self.detector = FaceDetector(upscale_threshold=160, max_dim=480)
        self.detector.detector = MagicMock()

    def test_large_crop_downscaled_and_coordinates_mapped_back(self):
        crop = np.zeros((960, 720, 3), dtype=np.uint8)  # cạnh dài 960 > 480 -> scale 0.5 -> 360x480
        detected_small = FaceDetectionResult(
            bbox=BoundingBox(x1=100.0, y1=50.0, x2=180.0, y2=150.0, confidence=0.9),
            score=0.9,
            landmarks=[(120.0, 80.0), (160.0, 80.0), (140.0, 100.0), (125.0, 125.0), (155.0, 125.0)],
        )
        with patch.object(self.detector, "detect_in_image", return_value=[detected_small]) as mock_detect:
            res = self.detector.detect_best_face_in_crop(crop, offset_xy=(1000, 200))

        resized = mock_detect.call_args[0][0]
        self.assertEqual(resized.shape[:2], (480, 360))
        self.assertEqual(mock_detect.call_args[1].get("offset_xy"), (0, 0))
        # x = 100/0.5 + 1000 = 1200 ; y = 50/0.5 + 200 = 300 ; x2 = 1360 ; y2 = 500
        self.assertAlmostEqual(res.bbox.x1, 1200.0, places=3)
        self.assertAlmostEqual(res.bbox.y1, 300.0, places=3)
        self.assertAlmostEqual(res.bbox.x2, 1360.0, places=3)
        self.assertAlmostEqual(res.bbox.y2, 500.0, places=3)
        self.assertAlmostEqual(res.landmarks[0][0], 1240.0, places=3)
        self.assertAlmostEqual(res.landmarks[0][1], 360.0, places=3)

    def test_medium_crop_untouched(self):
        crop = np.zeros((400, 300, 3), dtype=np.uint8)  # 160 <= cạnh, cạnh dài <= 480 -> giữ nguyên
        with patch.object(self.detector, "detect_in_image", return_value=[]) as mock_detect:
            self.detector.detect_best_face_in_crop(crop, offset_xy=(5, 6))
        self.assertIs(mock_detect.call_args[0][0], crop)
        self.assertEqual(mock_detect.call_args[1].get("offset_xy"), (5, 6))

    def test_max_dim_zero_disables_downscale(self):
        self.detector.max_dim = 0
        crop = np.zeros((960, 720, 3), dtype=np.uint8)
        with patch.object(self.detector, "detect_in_image", return_value=[]) as mock_detect:
            self.detector.detect_best_face_in_crop(crop, offset_xy=(0, 0))
        self.assertIs(mock_detect.call_args[0][0], crop)
