import unittest
from unittest.mock import MagicMock, patch
import numpy as np

from src.core.entity import BoundingBox, FaceDetectionResult
from src.core.face_detector import FaceDetector

class TestFaceCropUpscale(unittest.TestCase):
    @patch("os.path.exists", return_value=True)
    @patch("cv2.FaceDetectorYN.create")
    def setUp(self, mock_yn_create, mock_exists):
        self.detector = FaceDetector(upscale_threshold=160)
        self.detector.detector = MagicMock()

    def test_small_crop_upscaled_and_coordinates_mapped_accurately(self):
        """Crop nhỏ (< 160px) được phóng to trước khi detect, toạ độ bbox và landmarks quy đổi đúng về frame gốc"""
        # Crop kích thước w=80, h=100 (cạnh ngắn 80 < 160)
        crop = np.zeros((100, 80, 3), dtype=np.uint8)
        offset_xy = (200, 300)

        # scale = 160 / 80 = 2.0 -> resized crop sẽ là w=160, h=200
        # Giả lập YuNet phát hiện mặt trên ảnh phóng to tại [40, 50, 80, 90]
        detected_on_resized = FaceDetectionResult(
            bbox=BoundingBox(x1=40.0, y1=50.0, x2=80.0, y2=90.0, confidence=0.92),
            score=0.92,
            landmarks=[(50.0, 60.0), (70.0, 60.0), (60.0, 75.0), (55.0, 85.0), (65.0, 85.0)]
        )

        with patch.object(self.detector, "detect_in_image", return_value=[detected_on_resized]) as mock_detect:
            res = self.detector.detect_best_face_in_crop(crop, offset_xy=offset_xy)

            # Xác nhận detect_in_image được gọi với offset_xy=(0, 0) trên ảnh phóng to
            mock_detect.assert_called_once()
            call_args = mock_detect.call_args
            resized_img = call_args[0][0]
            self.assertEqual(resized_img.shape[1], 160)  # new_w = 160
            self.assertEqual(resized_img.shape[0], 200)  # new_h = 200
            self.assertEqual(call_args[1].get("offset_xy"), (0, 0))

            # Xác nhận quy đổi toạ độ về frame gốc chính xác:
            # x1 = 40 / 2.0 + 200 = 220
            # y1 = 50 / 2.0 + 300 = 325
            # x2 = 80 / 2.0 + 200 = 240
            # y2 = 90 / 2.0 + 300 = 345
            self.assertIsNotNone(res)
            self.assertAlmostEqual(res.bbox.x1, 220.0, places=3)
            self.assertAlmostEqual(res.bbox.y1, 325.0, places=3)
            self.assertAlmostEqual(res.bbox.x2, 240.0, places=3)
            self.assertAlmostEqual(res.bbox.y2, 345.0, places=3)

            # Landmarks quy đổi về frame gốc:
            # (50 / 2.0 + 200, 60 / 2.0 + 300) = (225, 330)
            self.assertEqual(len(res.landmarks), 5)
            self.assertAlmostEqual(res.landmarks[0][0], 225.0, places=3)
            self.assertAlmostEqual(res.landmarks[0][1], 330.0, places=3)

    def test_large_crop_no_upscale(self):
        """Crop đủ lớn (>= 160px) không bị phóng to, gọi detect_in_image trực tiếp kèm offset_xy gốc"""
        # Crop kích thước w=200, h=250 (cạnh ngắn 200 >= 160)
        crop = np.zeros((250, 200, 3), dtype=np.uint8)
        offset_xy = (100, 150)

        expected_res = FaceDetectionResult(
            bbox=BoundingBox(x1=150.0, y1=200.0, x2=190.0, y2=250.0, confidence=0.88),
            score=0.88,
            landmarks=[(160.0, 210.0), (180.0, 210.0), (170.0, 225.0), (165.0, 235.0), (175.0, 235.0)]
        )

        with patch.object(self.detector, "detect_in_image", return_value=[expected_res]) as mock_detect:
            res = self.detector.detect_best_face_in_crop(crop, offset_xy=offset_xy)

            mock_detect.assert_called_once_with(crop, offset_xy=offset_xy)
            self.assertEqual(res, expected_res)

if __name__ == "__main__":
    unittest.main()
