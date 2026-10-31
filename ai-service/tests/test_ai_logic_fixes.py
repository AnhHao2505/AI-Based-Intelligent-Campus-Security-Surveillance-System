import os
import sys
import unittest
import numpy as np
import time

# Ensure ai-service root is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import Point, BoundingBox, RoiPolygonConfig, FaceDetectionResult, TrackedPerson
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline

class TestAILogicFixes(unittest.TestCase):
    def test_frame_analysis_engine_2_value_unpacking(self):
        """
        Bug 1: FrameAnalysisEngine.process_frame phải hỗ trợ unpack 2 biến
        persons, alerts = engine.process_frame(...) mà không bị ValueError: too many values to unpack
        khi return_roi_events=False (hoặc mặc định)
        """
        engine = FrameAnalysisEngine()
        bbox = BoundingBox(100, 100, 200, 300)
        
        result = engine.process_frame(
            detected_tracks=[(1, bbox)],
            current_time=1000.0,
            return_roi_events=False
        )
        self.assertEqual(len(result), 2)
        persons, alerts = result
        self.assertEqual(len(persons), 1)

    def test_frame_analysis_engine_3_value_unpacking_when_requested(self):
        """
        FrameAnalysisEngine.process_frame khi truyền return_roi_events=True phải trả về 3 biến
        persons, alerts, roi_events = engine.process_frame(..., return_roi_events=True)
        """
        engine = FrameAnalysisEngine()
        bbox = BoundingBox(100, 100, 200, 300)
        
        result = engine.process_frame(
            detected_tracks=[(1, bbox)],
            current_time=1000.0,
            return_roi_events=True
        )
        self.assertEqual(len(result), 3)
        persons, alerts, roi_events = result
        self.assertEqual(len(persons), 1)

    def test_public_area_daytime_suppresses_unknown_alert(self):
        """
        Bug 4: Khu vực công cộng (is_public_area=True) ban ngày (after_hours=False)
        KHÔNG ĐƯỢC sinh cảnh báo UNKNOWN_PERSON đối với khách/sinh viên vãng lai qua cổng.
        """
        engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
        bbox = BoundingBox(100, 100, 200, 300)
        gate_poly = RoiPolygonConfig(
            label="Cổng chính",
            alert_rules=["ENTRY_EXIT_TRACKING"],
            vertices=[Point(0, 0), Point(500, 0), Point(500, 500), Point(0, 500)]
        )
        
        # 12:00 PM (Ban ngày)
        midday_time = 1761912000.0 # ~midday
        persons, alerts = engine.process_frame(
            detected_tracks=[(1, bbox)],
            roi_polygons=[gate_poly],
            frame_size=(640, 480),
            current_time=midday_time,
            is_public_area=True,
            return_roi_events=False
        )
        # Không có face trong DB -> Người lạ ban ngày ở Cổng chính -> Không cảnh báo!
        self.assertEqual(len(alerts), 0, f"Khu vực công cộng ban ngày không được báo UNKNOWN_PERSON, nhưng nhận: {[a.event_type for a in alerts]}")

    def test_suspicious_motion_suppressed_when_human_overlaps_motion(self):
        """
        Bug 2: Nếu có dáng người (human detection) nằm đè/trùng khớp với motion box
        thì đó là NGƯỜI, KHÔNG ĐƯỢC báo động nhầm SUSPICIOUS_MOTION (nghi vấn ngụy trang).
        """
        pipeline = VideoPipeline(camera_code="CAM-001", enable_motion_gating=False)
        motion_box = BoundingBox(100, 100, 200, 200)
        human_bbox = BoundingBox(90, 80, 220, 350)
        
        is_overlapping = pipeline._is_motion_overlapping_humans(motion_box, [human_bbox])
        self.assertTrue(is_overlapping)

    def test_face_recognition_cooldown_for_unknown_track(self):
        """
        Bug 6: Track ID không có trong database (Unknown face) phải có cooldown kiểm tra lại,
        tránh chạy ArcFace Deep Learning & pgvector 30 lần mỗi giây trên CPU.
        """
        pipeline = VideoPipeline(camera_code="CAM-001", enable_motion_gating=False)
        # Check initial should_check_face
        self.assertTrue(pipeline._should_check_face(track_id=99, current_time=100.0))
        
        # Record attempt
        pipeline._record_face_check_attempt(track_id=99, current_time=100.0, matched=False)
        
        # At 100.2s (< cooldown 1.5s), should NOT check face again
        self.assertFalse(pipeline._should_check_face(track_id=99, current_time=100.2))
        
        # At 102.0s (> cooldown 1.5s), should allow retry
        self.assertTrue(pipeline._should_check_face(track_id=99, current_time=102.0))

    def test_relative_storage_directories(self):
        """
        Bug 7: Đường dẫn ghi hình recording và evidence không được fix cứng C:\\DATN
        mà phải tự động tương đối theo repo root nếu không được cấu hình qua ENV.
        """
        from src.config import settings
        self.assertFalse(settings.RECORDING_BASE_DIR.startswith(r"C:\DATN"), "RECORDING_BASE_DIR không được hardcode C:\\DATN")
        self.assertFalse(settings.EVIDENCE_BASE_DIR.startswith(r"C:\DATN"), "EVIDENCE_BASE_DIR không được hardcode C:\\DATN")

if __name__ == "__main__":
    unittest.main()
