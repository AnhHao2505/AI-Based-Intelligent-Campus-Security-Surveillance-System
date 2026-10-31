import sys
import os
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.zone_state_machine import ZoneStateMachine, ZoneState

class TestZoneStateMachine(unittest.TestCase):
    def setUp(self):
        self.sm = ZoneStateMachine(
            cooldown_seconds=3.0,
            motion_to_occupied_seconds=1.0
        )

    def test_idle_to_motion_active(self):
        """Từ IDLE khi có chuyển động chuyển sang MOTION_ACTIVE"""
        self.assertEqual(self.sm.state, ZoneState.IDLE)
        self.assertFalse(self.sm.should_run_deep_learning)

        state = self.sm.update(has_motion=True, person_in_roi=False, current_time=100.0)
        self.assertEqual(state, ZoneState.MOTION_ACTIVE)
        self.assertTrue(self.sm.should_run_deep_learning)

    def test_instant_person_occupied(self):
        """Nếu frame đầu YOLO đã thấy Person trong ROI -> Lập tức vào ZONE_OCCUPIED"""
        state = self.sm.update(has_motion=True, person_in_roi=True, current_time=100.0)
        self.assertEqual(state, ZoneState.ZONE_OCCUPIED)
        self.assertTrue(self.sm.is_occupied)

    def test_camouflage_sustained_motion_to_occupied(self):
        """Kẻ ngụy trang: Chuyển động duy trì > 1.0s chuyển sang ZONE_OCCUPIED"""
        self.sm.update(has_motion=True, person_in_roi=False, current_time=100.0)
        self.assertEqual(self.sm.state, ZoneState.MOTION_ACTIVE)

        # Chuyển động tiếp tục ở t = 101.2s (> 1.0s)
        state = self.sm.update(has_motion=True, person_in_roi=False, current_time=101.2)
        self.assertEqual(state, ZoneState.ZONE_OCCUPIED)

    def test_stationary_intruder_latched(self):
        """Kẻ trộm vào phòng rồi đứng bất động -> Trạng thái COOLDOWN rồi mới IDLE sau 3s"""
        # Bước vào phòng
        self.sm.update(has_motion=True, person_in_roi=True, current_time=100.0)
        self.assertEqual(self.sm.state, ZoneState.ZONE_OCCUPIED)

        # Đứng im nấp (không còn motion, giả sử bị che khuất tạm thời person=False ở t=101s)
        state = self.sm.update(has_motion=False, person_in_roi=False, current_time=101.0)
        self.assertEqual(state, ZoneState.COOLDOWN)
        self.assertTrue(self.sm.is_occupied)  # Vẫn chốt giữ là occupied!

        # Nhúc nhích lại ở t = 102s (< 3s cooldown) -> Quay lại OCCUPIED ngay
        state = self.sm.update(has_motion=True, person_in_roi=False, current_time=102.0)
        self.assertEqual(state, ZoneState.ZONE_OCCUPIED)

    def test_cooldown_to_idle(self):
        """Hết thời gian cooldown (3s) an toàn -> Quay về IDLE"""
        self.sm.update(has_motion=True, person_in_roi=True, current_time=100.0)
        self.sm.update(has_motion=False, person_in_roi=False, current_time=101.0)
        self.assertEqual(self.sm.state, ZoneState.COOLDOWN)

        # Thời gian trôi qua 3.5s (> 3.0s cooldown)
        state = self.sm.update(has_motion=False, person_in_roi=False, current_time=104.5)
        self.assertEqual(state, ZoneState.IDLE)
        self.assertFalse(self.sm.is_occupied)

if __name__ == "__main__":
    unittest.main()
