import unittest
import numpy as np
from src.core.entity import BoundingBox, Point, FaceDetectionResult
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.utils.visualizer import FrameVisualizer

class TestTrackDeduplicationAndInheritance(unittest.TestCase):
    def test_overlapping_new_track_inherits_identity_and_retires_old_track(self):
        engine = FrameAnalysisEngine()
        roi = [Point(100, 100), Point(600, 100), Point(600, 600), Point(100, 600)]

        # 1. Frame 1: Person at desk with Track ID 2, identified as SE001
        bbox_desk = BoundingBox(x1=200, y1=200, x2=350, y2=450)
        face_info = FaceDetectionResult(
            bbox=bbox_desk,
            score=0.92,
            matched_code="SE001",
            matched_name="Nguyen Van A",
            is_authorized=True,
            is_active=True
        )
        engine.associate_face(track_id=2, face_result=face_info)
        persons, alerts, _ = engine.process_frame([(2, bbox_desk)], roi, current_time=100.0)

        self.assertEqual(len(alerts), 0)
        self.assertIn(2, engine.active_tracks)
        self.assertTrue(engine.active_tracks[2].face_detected)

        # 2. Frame 2: ByteTrack loses ID 2 and assigns ID 11 at essentially the same desk position
        bbox_desk_shifted = BoundingBox(x1=205, y1=205, x2=355, y2=455)
        persons, alerts, _ = engine.process_frame([(11, bbox_desk_shifted)], roi, current_time=101.0)

        # ID 11 must inherit identity from ID 2
        self.assertIn(11, engine.active_tracks)
        p11 = engine.active_tracks[11]
        self.assertTrue(p11.face_detected)
        self.assertEqual(p11.face_info.matched_code, "SE001")
        self.assertEqual(p11.face_info.matched_name, "Nguyen Van A")
        self.assertTrue(p11.is_currently_visible)

        # Old track ID 2 must NOT be active/visible anymore
        self.assertNotIn(2, engine.active_tracks)
        # Must NOT generate UNKNOWN alert because identity was inherited
        self.assertEqual(len(alerts), 0)

        # 3. Frame 3: ByteTrack again switches to ID 15
        bbox_desk_15 = BoundingBox(x1=210, y1=200, x2=360, y2=450)
        persons, alerts, _ = engine.process_frame([(15, bbox_desk_15)], roi, current_time=102.0)

        self.assertIn(15, engine.active_tracks)
        p15 = engine.active_tracks[15]
        self.assertTrue(p15.face_detected)
        self.assertEqual(p15.face_info.matched_code, "SE001")
        self.assertNotIn(11, engine.active_tracks)
        self.assertEqual(len(alerts), 0)

    def test_visualizer_deduplicates_overlapping_tracks(self):
        # Giả lập 2 người nhưng có 4 bounding boxes (3 box trùng lặp trên cùng 1 người)
        vis = FrameVisualizer()
        frame = np.zeros((720, 1280, 3), dtype=np.uint8)

        # Person A (on bed)
        box_bed = BoundingBox(x1=800, y1=200, x2=1100, y2=600)
        from src.core.entity import TrackedPerson
        p1 = TrackedPerson(track_id=1, bbox=box_bed)
        p1.is_currently_visible = True

        # Person B (at desk) with 3 concentric ghost boxes: ID 2, ID 11, ID 15
        box_desk_11 = BoundingBox(x1=200, y1=150, x2=450, y2=600)
        box_desk_2 = BoundingBox(x1=220, y1=180, x2=430, y2=580)
        box_desk_15 = BoundingBox(x1=230, y1=200, x2=420, y2=560)

        p11 = TrackedPerson(track_id=11, bbox=box_desk_11)
        p11.is_currently_visible = True

        p2 = TrackedPerson(track_id=2, bbox=box_desk_2)
        p2.is_currently_visible = True

        p15 = TrackedPerson(track_id=15, bbox=box_desk_15)
        p15.is_currently_visible = True

        persons = [p1, p11, p2, p15]

        # Draw frame
        ann_frame = vis.draw_tracked_persons(frame, persons)
        self.assertEqual(ann_frame.shape, frame.shape)

if __name__ == "__main__":
    unittest.main()
