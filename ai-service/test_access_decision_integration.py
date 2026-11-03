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
        """Test FrameAnalysisEngine sinh cảnh báo UNAUTHORIZED_ACCESS kèm đúng lý do từ checkEntry"""
        engine = FrameAnalysisEngine(after_hour_start="23:59", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        # Gán mặt có trong DB nhưng không có quyền (is_authorized=False) kèm access_reason
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

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(1, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        alert = alerts[0]
        self.assertEqual(alert.event_type, "UNAUTHORIZED_ACCESS")
        self.assertIn("Trần Sinh Viên", alert.details)
        self.assertIn("Phòng Server Tầng 3", alert.details)
        self.assertIn("Cấp độ người dùng chưa đủ điều kiện vào phòng máy chủ", alert.details)
        self.assertTrue(persons[0].alert_unauthorized_sent)

    def test_frame_analysis_engine_unknown_person_alert(self):
        """Test FrameAnalysisEngine sinh cảnh báo UNKNOWN_PERSON khi người lạ không có trong database"""
        engine = FrameAnalysisEngine(after_hour_start="23:59", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        # Gán mặt phát hiện được nhưng không khớp ai trong database (matched_code=None, matched_name=None)
        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.88,
            matched_code=None,
            matched_name=None,
            is_authorized=False,
            is_active=True
        )
        engine.associate_face(track_id=2, face_result=face_info)

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(2, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        alert = alerts[0]
        self.assertEqual(alert.event_type, "UNKNOWN_PERSON")
        self.assertIn("người lạ", alert.details)
        self.assertTrue(persons[0].alert_unknown_sent)

    def test_frame_analysis_engine_daytime_unclear_face(self):
        """Test Trong giờ hoạt động nhưng chưa rõ mặt trong vùng ROI -> Gán UNKNOWN_PERSON (Quy tắc 'chưa rõ mặt là gán unknow luôn')"""
        engine = FrameAnalysisEngine(after_hour_start="23:59", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        # Đối tượng trong ROI nhưng chưa phát hiện được mặt (quay lưng / che mặt)
        t0 = time.time()
        engine.process_frame(detected_tracks=[(7, bbox)], roi_polygon=roi, camera_code="CAM-SERVER-01", current_time=t0)
        persons, alerts, _ = engine.process_frame(detected_tracks=[(7, bbox)], roi_polygon=roi, camera_code="CAM-SERVER-01", current_time=t0 + 2.0)

        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].event_type, "UNKNOWN_PERSON")
        self.assertIn("[UNKNOWN_PERSON]", alerts[0].details)
        self.assertIn("chưa rõ mặt", alerts[0].details)
        self.assertTrue(persons[0].alert_unknown_sent)

    def test_frame_analysis_engine_after_hours_unclear_face(self):
        """Test Ngoài giờ hoạt động nhưng chưa rõ mặt -> Gán INTRUSION kèm tag [INTRUSION]"""
        engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        # Chưa phát hiện mặt (quay lưng / che mặt ban đêm) -> Gán INTRUSION
        t0 = time.time()
        engine.process_frame(detected_tracks=[(3, bbox)], roi_polygon=roi, camera_code="CAM-SERVER-01", current_time=t0)
        persons, alerts, _ = engine.process_frame(detected_tracks=[(3, bbox)], roi_polygon=roi, camera_code="CAM-SERVER-01", current_time=t0 + 2.0)

        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].event_type, "INTRUSION")
        self.assertIn("[INTRUSION]", alerts[0].details)
        self.assertIn("chưa rõ mặt", alerts[0].details)
        self.assertTrue(persons[0].alert_unknown_sent)

    def test_frame_analysis_engine_after_hours_authorized_guard(self):
        """Test Ngoài giờ nhưng là người có phân công trực (Bảo vệ trực ca) -> HỢP LỆ, KHÔNG sinh sự cố"""
        engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.98,
            matched_code="BV001",
            matched_name="Nguyễn Văn Bảo Vệ",
            is_authorized=True,
            is_active=True,
            access_reason="Người dùng được gán vào khu vực",
            area_name="Cổng Chính"
        )
        engine.associate_face(track_id=4, face_result=face_info)

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(4, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 0)  # Bảo vệ trực ca hợp lệ

    def test_frame_analysis_engine_after_hours_student_denied(self):
        """Test Ngoài giờ: Sinh viên có trong DB nhưng chỉ có quyền ban ngày (không có đơn trực đêm) -> UNAUTHORIZED_ACCESS"""
        engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.95,
            matched_code="SE170123",
            matched_name="Trần Sinh Viên",
            is_authorized=True,
            is_active=True,
            access_reason="Cấp độ truy cập của người dùng phù hợp với khu vực",  # Chỉ là quyền level ban ngày
            area_name="Thư viện Tầng 2"
        )
        engine.associate_face(track_id=6, face_result=face_info)

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(6, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].event_type, "INTRUSION")
        self.assertIn("[INTRUSION]", alerts[0].details)
        self.assertTrue(persons[0].alert_unauthorized_sent)

    def test_frame_analysis_engine_after_hours_unknown_intruder(self):
        """Test Ngoài giờ và là người lạ ngoài hệ thống -> Sinh INTRUSION kèm tag [INTRUSION]"""
        engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.85,
            matched_code=None,
            matched_name=None,
            is_authorized=False,
            is_active=True
        )
        engine.associate_face(track_id=5, face_result=face_info)

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(5, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].event_type, "INTRUSION")
        self.assertIn("[INTRUSION]", alerts[0].details)
        self.assertIn("ngoài giờ hoạt động", alerts[0].details)
        self.assertTrue(persons[0].alert_unknown_sent)

    def test_frame_analysis_engine_soft_deleted_user_classified_as_unknown_person(self):
        """Test Người dùng đã bị xoá mềm trong DB khi Backend phản hồi 'Người dùng không tồn tại' -> Gán UNKNOWN_PERSON"""
        engine = FrameAnalysisEngine(after_hour_start="23:59", after_hour_end="23:59")
        roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
        bbox = BoundingBox(x1=200, y1=200, x2=300, y2=300)

        face_info = FaceDetectionResult(
            bbox=bbox,
            score=0.91,
            matched_code="SE194249",
            matched_name="Đặng Nguyễn Gia Minh",
            is_authorized=False,
            is_active=True,
            access_reason="Người dùng không tồn tại"
        )
        engine.associate_face(track_id=8, face_result=face_info)

        persons, alerts, _ = engine.process_frame(
            detected_tracks=[(8, bbox)],
            roi_polygon=roi,
            camera_code="CAM-SERVER-01",
            current_time=time.time()
        )

        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].event_type, "UNKNOWN_PERSON")
        self.assertIn("[UNKNOWN_PERSON]", alerts[0].details)
        self.assertNotIn("SE194249", alerts[0].details)
        self.assertNotIn("Đặng Nguyễn Gia Minh", alerts[0].details)

if __name__ == "__main__":
    unittest.main()
