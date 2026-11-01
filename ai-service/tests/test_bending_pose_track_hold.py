import unittest
import numpy as np
from src.core.entity import BoundingBox, Point, RoiPolygonConfig, TrackedPerson, MotionResult
from src.pipeline.video_pipeline import VideoPipeline
from src.core.motion_detector import MotionDetector
from src.utils.visualizer import FrameVisualizer
from src.core.frame_analysis_engine import FrameAnalysisEngine


class TestMotionSuppressionAndPoseChange(unittest.TestCase):
    def test_merge_close_boxes_reduces_fragmented_motion(self):
        # Giả lập 5 boxes chuyển động rời rạc như trong ảnh (2 bàn chân, bóng đổ, đầu gối)
        boxes = [
            BoundingBox(x1=540.0, y1=750.0, x2=580.0, y2=850.0, confidence=0.8),  # Chân trái
            BoundingBox(x1=600.0, y1=780.0, x2=640.0, y2=860.0, confidence=0.8),  # Chân phải
            BoundingBox(x1=570.0, y1=790.0, x2=610.0, y2=840.0, confidence=0.7),  # Vùng giữa 2 chân
            BoundingBox(x1=550.0, y1=720.0, x2=630.0, y2=760.0, confidence=0.9),  # Đùi/gối
        ]
        merged = MotionDetector._merge_close_boxes(boxes, distance_thresh=50.0)
        self.assertEqual(len(merged), 1, "Tất cả các phần rời rạc của cùng 1 người phải được hợp nhất thành 1 bounding box duy nhất")
        self.assertEqual(merged[0].x1, 540.0)
        self.assertEqual(merged[0].x2, 640.0)
        self.assertEqual(merged[0].y1, 720.0)
        self.assertEqual(merged[0].y2, 860.0)

    def test_bending_person_retains_track_id_and_suppresses_camouflage_alert(self):
        # Giả lập FrameAnalysisEngine có người đang được track: Track ID = 1
        engine = FrameAnalysisEngine(max_inactive_seconds=2.0)
        person_box = BoundingBox(x1=500.0, y1=200.0, x2=700.0, y2=800.0)
        engine.process_frame([(1, person_box)], camera_code="CAM-TEST", current_time=100.0)

        self.assertIn(1, engine.active_tracks)
        p = engine.active_tracks[1]
        self.assertTrue(p.is_currently_visible)

        # Frame tiếp theo (100.5s): Người cúi người/ngồi xuống, YOLO trượt detection -> detected_tracks = []
        # Nhưng có chuyển động tại vị trí người đó:
        motion_boxes = [BoundingBox(x1=520.0, y1=600.0, x2=680.0, y2=820.0)]
        motion_res = MotionResult(has_motion=True, motion_area=8000, motion_boxes=motion_boxes)

        # Chạy logic liên kết motion và active tracks như trong video_pipeline.py
        current_time = 100.5
        current_frame_tids = set() # Không có detection từ YOLO
        active_tracked_boxes = []

        for tid, trk in engine.active_tracks.items():
            inactive_dur = current_time - trk.last_seen_time
            if inactive_dur <= engine.max_inactive_seconds:
                overlapping_mb = [
                    mb for mb in motion_res.motion_boxes
                    if VideoPipeline._is_box_overlapping_any(mb, [trk.bbox], overlap_threshold=0.08)
                ]
                if overlapping_mb:
                    trk.last_seen_time = current_time
                    trk.is_currently_visible = True
                active_tracked_boxes.append(trk.bbox)

        # Kiểm tra chuyển động bị triệt tiêu 100%
        all_human_boxes = active_tracked_boxes
        non_human_mb = [
            mb for mb in motion_res.motion_boxes
            if not VideoPipeline._is_box_overlapping_any(mb, all_human_boxes, overlap_threshold=0.08)
        ]
        motion_res.motion_boxes = non_human_mb

        # 1. Track ID 1 phải được giữ nguyên và vẫn visible
        self.assertTrue(p.is_currently_visible, "Track ID 1 phải tiếp tục được hiển thị trên màn hình")
        self.assertEqual(p.last_seen_time, 100.5, "Track ID 1 phải được cập nhật thời gian sống qua chuyển động")

        # 2. Toàn bộ motion box của người cúi xuống phải bị lọc sạch
        self.assertEqual(len(motion_res.motion_boxes), 0, "Không được còn bất kỳ motion box nào của người")


if __name__ == "__main__":
    unittest.main()
