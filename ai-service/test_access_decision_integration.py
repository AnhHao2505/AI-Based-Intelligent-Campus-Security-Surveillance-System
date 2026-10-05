"""
Unit test kiểm thử tính năng kết nối xét quyền ra vào Camera (checkEntry)
giữa AI Service và Spring Boot Access Decision Service.
"""
import sys
import os
import time
import unittest
from unittest.mock import patch, MagicMock

# Tự động set UTF-8 cho console Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.face_matcher import FaceMatcher
from src.core.entity import FaceDetectionResult, BoundingBox
from src.core.frame_analysis_engine import FrameAnalysisEngine, Point

class TestAccessDecisionIntegration(unittest.TestCase):

    def setUp(self):
        with patch('psycopg2.connect') as mock_conn:
            mock_conn.side_effect = Exception("DB offline for testing")
            self.matcher = FaceMatcher()
        self.matcher.cached_faces = [
            {"code": "GV001", "full_name": "Nguyễn Văn Thầy", "vector": [0.0]*512, "is_active": True},
            {"code": "SE170123", "full_name": "Trần Sinh Viên", "vector": [0.0]*512, "is_active": True},
            {"code": "LOCKED01", "full_name": "Tài Khoản Bị Khóa", "vector": [0.0]*512, "is_active": False}
        ]

    @patch('requests.post')
    def test_check_camera_access_allowed(self, mock_post):
        """Test khi Backend checkEntry trả về ALLOWED"""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {
            "success": True,
            "data": {
                "allowed": True,
                "source": "ASSIGNED_PERSONNEL",
                "reason": "Người dùng được gán vào khu vực",
                "cameraCode": "CAM-01",
                "areaName": "Phòng Lab 01",
                "userCode": "GV001"
            }
        }
        mock_post.return_value = mock_resp

        allowed, reason, area_name = self.matcher.check_camera_access("CAM-01", "GV001")
        self.assertTrue(allowed)
        self.assertEqual(reason, "Người dùng được gán vào khu vực")
        self.assertEqual(area_name, "Phòng Lab 01")

        # Kiểm tra Cache RAM: Lần 2 không gọi requests.post nữa
        mock_post.reset_mock()
        allowed2, reason2, area_name2 = self.matcher.check_camera_access("CAM-01", "GV001")
        self.assertTrue(allowed2)
        mock_post.assert_not_called()

    @patch('requests.post')
    def test_check_camera_access_denied(self, mock_post):
        """Test khi Backend checkEntry trả về DENIED (Không có quyền vào phòng)"""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {
            "success": True,
            "data": {
                "allowed": False,
                "source": "NONE",
                "reason": "Không có quyền ra vào khu vực tại thời điểm này",
                "cameraCode": "CAM-01",
                "areaName": "Phòng Lab 01",
                "userCode": "SE170123"
            }
        }
        mock_post.return_value = mock_resp

        allowed, reason, area_name = self.matcher.check_camera_access("CAM-01", "SE170123")
        self.assertFalse(allowed)
        self.assertEqual(reason, "Không có quyền ra vào khu vực tại thời điểm này")
        self.assertEqual(area_name, "Phòng Lab 01")

    def test_frame_analysis_engine_unauthorized_alert_with_reason(self):
        """Test FrameAnalysisEngine sinh cảnh báo UNAUTHORIZED kèm đúng lý do từ checkEntry"""
        engine = FrameAnalysisEngine()
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        # Gán mặt không có quyền (is_authorized=False) kèm access_reason
        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.95,
            matched_code="SE170123",
            matched_name="Trần Sinh Viên",
            is_authorized=False,
            is_active=True,
            access_reason="Cấp độ người dùng chưa đủ điều kiện vào phòng máy chủ",
            area_name="Phòng Server Tầng 3"
        )
        engine.associate_face(track_id=1, face_result=face_info)

        persons, alerts = engine.process_frame(
            detected_tracks=[(1, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        alert = alerts[0]
        self.assertEqual(alert.event_type, "UNAUTHORIZED")
        self.assertIn("Trần Sinh Viên", alert.details)
        self.assertIn("Phòng Server Tầng 3", alert.details)
        self.assertIn("Cấp độ người dùng chưa đủ điều kiện vào phòng máy chủ", alert.details)

if __name__ == "__main__":
    unittest.main()
