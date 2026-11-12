import time
import unittest
from unittest.mock import MagicMock, patch
import numpy as np

from src.core.entity import BoundingBox, FaceDetectionResult
from src.pipeline.video_pipeline import VideoPipeline

class TestIdentifyLatency(unittest.TestCase):
    def setUp(self):
        self.pipeline = VideoPipeline(camera_code="CAM-TEST-01")
        # Mock human detector to avoid loading YOLO model
        self.pipeline.human_detector = MagicMock()
        self.pipeline.face_detector = MagicMock()
        self.pipeline.face_matcher = MagicMock()
        self.pipeline.face_matcher.last_best_score = 0.0
        self.pipeline.face_matcher.get_cached_access.return_value = None
        self.pipeline.face_matcher.is_camera_public.return_value = False
        self.pipeline.face_matcher.get_camera_area_name.return_value = "Phòng Server"

        self.pipeline.enable_motion_gating = False
        from src.core.entity import MotionResult
        self.pipeline.motion_detector = MagicMock()
        self.pipeline.motion_detector.detect.return_value = MotionResult(has_motion=True, has_motion_full=True)

    def test_drops_counting(self):
        """Kiểm tra đếm đúng các lý do bỏ qua: không mặt, nhỏ, mờ, dưới ngưỡng"""
        self.pipeline.face_scan_throttling_enabled = False
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        current_time = 100.0
        track_id = 1
        bbox = BoundingBox(x1=100, y1=100, x2=200, y2=300)

        # 1. Không thấy mặt (YuNet trả None)
        self.pipeline.face_detector.detect_best_face_in_crop.return_value = None
        self.pipeline.human_detector.detect_and_track.return_value = [(track_id, bbox)]
        self.pipeline.process_frame(frame, current_time=current_time)

        stats = self.pipeline._track_identify_stats.get(track_id)
        self.assertIsNotNone(stats)
        self.assertEqual(stats["drop_no_face"], 1)

        # 2. Mặt quá nhỏ (< face_min_crop_size)
        small_face = FaceDetectionResult(
            bbox=BoundingBox(x1=120, y1=120, x2=130, y2=130), # 10x10 < 24
            score=0.9
        )
        self.pipeline.face_detector.detect_best_face_in_crop.return_value = small_face
        self.pipeline.process_frame(frame, current_time=current_time + 1.0)
        self.assertEqual(stats["drop_too_small"], 1)

        # 3. Mặt bị mờ (_is_face_blurry = True)
        normal_face = FaceDetectionResult(
            bbox=BoundingBox(x1=120, y1=120, x2=170, y2=170), # 50x50 >= 24
            score=0.9
        )
        self.pipeline.face_detector.detect_best_face_in_crop.return_value = normal_face
        with patch.object(self.pipeline, "_is_face_blurry", return_value=True):
            self.pipeline.process_frame(frame, current_time=current_time + 2.0)
        self.assertEqual(stats["drop_blurry"], 1)

        # 4. ArcFace chạy nhưng score < threshold
        self.pipeline.face_matcher.match_face.return_value = None
        self.pipeline.face_matcher.last_best_score = 0.47
        with patch.object(self.pipeline, "_is_face_blurry", return_value=False):
            self.pipeline.process_frame(frame, current_time=current_time + 3.0)
        self.assertEqual(stats["drop_low_score"], 1)
        self.assertAlmostEqual(stats["best_unmatched_score"], 0.47, places=2)

    def test_identify_latency_log_format(self):
        """Kiểm tra format dòng log INFO tóm tắt độ trễ nhận diện"""
        current_time = 100.0
        track_id = 12
        stats = {
            "first_seen_time": current_time,
            "first_matched_time": current_time + 0.42,
            "access_checked_time": current_time + 0.42 + 0.18,
            "matched_code": "SV001",
            "drop_no_face": 3,
            "drop_too_small": 1,
            "drop_blurry": 2,
            "drop_low_score": 4,
            "best_unmatched_score": 0.47,
            "latency_logged": False
        }

        with self.assertLogs("src.pipeline.video_pipeline", level="INFO") as cm:
            self.pipeline._log_identify_latency(track_id, stats, current_time + 1.0)
            log_output = "\n".join(cm.output)
            self.assertIn("⏱️ [IdentifyLatency:CAM-TEST-01] Track #12 -> SV001:", log_output)
            self.assertIn("thấy→khớp 0.42s", log_output)
            self.assertIn("khớp→có quyền 0.18s", log_output)
            self.assertIn("bỏ: không mặt=3, nhỏ=1, mờ=2, dưới ngưỡng=4 (best 0.47)", log_output)

        # Kiểm tra không log lần thứ hai (chỉ in MỘT dòng)
        with self.assertRaises(AssertionError):
            with self.assertLogs("src.pipeline.video_pipeline", level="INFO"):
                self.pipeline._log_identify_latency(track_id, stats, current_time + 2.0)

    def test_track_identify_stats_cleanup_on_removal(self):
        """Bộ đệm _track_identify_stats được dọn dẹp sạch khi track biến mất khỏi tầm nhìn"""
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        current_time = 100.0
        track_id = 99
        bbox = BoundingBox(x1=100, y1=100, x2=200, y2=300)

        self.pipeline.face_detector.detect_best_face_in_crop.return_value = None
        self.pipeline.human_detector.detect_and_track.return_value = [(track_id, bbox)]
        self.pipeline.process_frame(frame, current_time=current_time)

        self.assertIn(track_id, self.pipeline._track_identify_stats)

        # Cho track biến mất quá max_inactive_seconds
        self.pipeline.human_detector.detect_and_track.return_value = []
        # FrameAnalysisEngine default max_inactive_seconds is 2.0s
        with self.assertLogs("src.pipeline.video_pipeline", level="INFO") as cm:
            self.pipeline.process_frame(frame, current_time=current_time + 5.0)
            log_output = "\n".join(cm.output)
            self.assertIn("⏱️ [IdentifyLatency:CAM-TEST-01] Track #99 -> CHƯA ĐỊNH DANH", log_output)

        # Xác nhận đã dọn dẹp khỏi dict
        self.assertNotIn(track_id, self.pipeline._track_identify_stats)

if __name__ == "__main__":
    unittest.main()


def test_inherited_identity_does_not_log_bogus_latency(caplog):
    """Track đã có danh tính sẵn (kế thừa) không được in dòng IdentifyLatency thứ hai với 'khớp→có quyền' vô nghĩa."""
    import logging
    import time as _time
    from unittest.mock import MagicMock
    from src.core.entity import BoundingBox, TrackedPerson, FaceDetectionResult
    from src.pipeline.video_pipeline import VideoPipeline

    p = VideoPipeline.__new__(VideoPipeline)
    p.camera_code = "CAM-T"
    p._track_identify_stats = {}
    import threading
    p._identify_stats_lock = threading.Lock()
    stats = {"first_seen_time": _time.time(), "first_matched_time": None, "access_checked_time": None,
             "matched_code": None, "drop_no_face": 0, "drop_too_small": 0, "drop_blurry": 0,
             "drop_low_score": 0, "best_unmatched_score": 0.0, "latency_logged": False}
    p._track_identify_stats[7] = stats
    # Mô phỏng nhánh "existing đã có danh tính": đánh dấu như code thật
    stats["first_matched_time"] = _time.time(); stats["matched_code"] = "SE1"; stats["latency_logged"] = True
    with caplog.at_level(logging.INFO, logger="src.pipeline.video_pipeline"):
        p._record_access_checked(7, "SE1", current_time=_time.time() + 55)
        p._log_identify_latency(7, stats, _time.time() + 60)
    assert not [r for r in caplog.records if "IdentifyLatency" in r.getMessage()]
