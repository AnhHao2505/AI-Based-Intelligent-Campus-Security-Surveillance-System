import unittest
from unittest.mock import patch
import numpy as np

from src.core.entity import BoundingBox, TrackedPerson, FaceDetectionResult
from src.utils.visualizer import FrameVisualizer

class TestVisualizerVerifyingName(unittest.TestCase):
    def setUp(self):
        self.visualizer = FrameVisualizer()
        self.frame = np.zeros((480, 640, 3), dtype=np.uint8)

    def test_verifying_state_shows_name_when_face_matched_before_backend(self):
        """Khi track có matched_code nhưng access_checked=False (VERIFYING), overlay hiện tên người kèm 'Dang kiem tra quyen'"""
        person = TrackedPerson(
            track_id=10,
            bbox=BoundingBox(x1=100, y1=100, x2=200, y2=300),
            is_in_roi=True,
            security_display_state="VERIFYING",
            face_detected=True,
            face_info=FaceDetectionResult(
                bbox=BoundingBox(x1=120, y1=120, x2=170, y2=170),
                score=0.9,
                matched_code="GV001",
                matched_name="Thầy Nguyễn Văn A",
                is_authorized=False,
                access_checked=False  # Đang chờ backend
            )
        )

        with patch("cv2.putText") as mock_put_text:
            self.visualizer.draw_tracked_persons(self.frame, [person], only_in_roi=False)
            self.assertTrue(mock_put_text.called)
            # Kiểm tra text label được vẽ
            drawn_labels = [call_args[0][1] for call_args in mock_put_text.call_args_list]
            found = any("Thay Nguyen Van A - Dang kiem tra quyen" in lbl or "GV001 - Dang kiem tra quyen" in lbl for lbl in drawn_labels)
            self.assertTrue(found, f"Không tìm thấy nhãn chứa tên và 'Dang kiem tra quyen' trong: {drawn_labels}")

    def test_verifying_state_shows_generic_label_when_no_face_matched(self):
        """Khi track chưa khớp khuôn mặt (chưa có matched_code), overlay hiện 'DANG XAC THUC...'"""
        person = TrackedPerson(
            track_id=11,
            bbox=BoundingBox(x1=100, y1=100, x2=200, y2=300),
            is_in_roi=True,
            security_display_state="VERIFYING",
            face_detected=False,
            face_info=None
        )

        with patch("cv2.putText") as mock_put_text:
            self.visualizer.draw_tracked_persons(self.frame, [person], only_in_roi=False)
            self.assertTrue(mock_put_text.called)
            drawn_labels = [call_args[0][1] for call_args in mock_put_text.call_args_list]
            found = any("DANG XAC THUC..." in lbl for lbl in drawn_labels)
            self.assertTrue(found, f"Không tìm thấy nhãn 'DANG XAC THUC...' trong: {drawn_labels}")

if __name__ == "__main__":
    unittest.main()
