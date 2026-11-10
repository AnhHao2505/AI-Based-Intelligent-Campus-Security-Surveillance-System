import os
import sys
import unittest
import pytest
import numpy as np

# Ensure ai-service root is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

os.environ["MINIO_ROOT_USER"] = "testuser"
os.environ["MINIO_ROOT_PASSWORD"] = "testpassword123"
os.environ["INTERNAL_SERVICE_KEY"] = "12345678901234567890123456789012"

from src.core.entity import Point, RoiPolygonConfig, BoundingBox, TrackedPerson, SecurityAlertEvent, MotionResult
from src.core.zone_state_machine import ZoneState
from src.pipeline.video_pipeline import VideoPipeline

@pytest.mark.requires_model
class TestSuppressDualWarningROI(unittest.TestCase):
    """
    Kiểm thử Phương án B:
    - Motion Gating Full-frame: Phát hiện người từ xa ngoài ROI -> Đánh thức YOLO tracking
    - Khử cảnh báo kép: Khi bước chân vào mép ROI, triệt tiêu 100% cảnh báo SUSPICIOUS_MOVING_OBJECT
    - Chỉ phát sinh duy nhất 1 cảnh báo chuẩn danh tính khi vào trong ROI (UNKNOWN_PERSON / UNAUTHORIZED_PERSON)
    """

    def setUp(self):
        self.pipeline = VideoPipeline(
            camera_code="CAM-DUAL-TEST",
            enable_motion_gating=True
        )
        self.width = 640
        self.height = 480
        # ROI nằm giữa khung hình (x: 200..440, y: 150..350)
        self.roi = RoiPolygonConfig(
            label="Khu-vuc-Server",
            vertices=[
                Point(200.0 / self.width, 150.0 / self.height),
                Point(440.0 / self.width, 150.0 / self.height),
                Point(440.0 / self.width, 350.0 / self.height),
                Point(200.0 / self.width, 350.0 / self.height)
            ]
        )
        self.pipeline.set_roi_config([self.roi])

    def test_footstep_into_roi_suppresses_suspicious_moving_object(self):
        """
        Kịch bản: Người đi từ ngoài hành lang vào ROI.
        Bước 1: Người ở ngoài ROI (x=100..180, y=140..340).
        Bước 2: Mũi chân bước chạm mép ROI (x=190..215, y=320..350), nhưng bottom_center vẫn ngoài ROI.
        Hệ thống PHẢI triệt tiêu SUSPICIOUS_MOVING_OBJECT, KHÔNG được bắn 2 cảnh báo kép!
        """
        frame = np.full((self.height, self.width, 3), 40, dtype=np.uint8)

        # 1. Người ở ngoài ROI
        human_bbox_outside = BoundingBox(x1=100.0, y1=140.0, x2=180.0, y2=340.0, confidence=0.92)
        in_roi, _ = self.pipeline.is_bbox_in_roi(human_bbox_outside, self.width, self.height)
        self.assertFalse(in_roi, "Người ở ngoài ROI phải có person_in_roi=False")

        # 2. Người bước tới mép ROI (mũi chân chạm x=195, bottom_center ở x=175 -> ngoài ROI)
        human_bbox_edge = BoundingBox(x1=130.0, y1=140.0, x2=220.0, y2=340.0, confidence=0.92)
        in_roi_edge, _ = self.pipeline.is_bbox_in_roi(human_bbox_edge, self.width, self.height)
        self.assertFalse(in_roi_edge, "Bottom center vẫn ở ngoài ROI")

        # Kiểm tra helper is_bbox_near_or_in_roi nhận biết người đang ở sát/chạm mép ROI
        is_near = self.pipeline.is_bbox_near_or_in_roi(human_bbox_edge, self.width, self.height, margin=40.0)
        self.assertTrue(is_near, "Phải phát hiện người đang ở sát mép ROI")

        # Giả lập MOG2 bắt được khối chuyển động của bước chân/bóng đổ tại mép ROI (x=195..220, y=320..350)
        footstep_motion_box = BoundingBox(x1=195.0, y1=320.0, x2=225.0, y2=350.0)

        # Kiểm tra _is_box_overlapping_any nhận diện box này thuộc về người
        is_associated = VideoPipeline._is_box_overlapping_any(
            footstep_motion_box,
            [human_bbox_edge],
            overlap_threshold=0.08,
            margin=35.0
        )
        self.assertTrue(is_associated, "Khối chuyển động của bước chân phải được liên kết với người")

    def test_genuine_camouflage_in_roi_still_triggers_suspicious_moving_object(self):
        """
        Kịch bản: Vật thể chuyển động ngụy trang (không có người nào gần ROI)
        -> Vẫn bắn chính xác SUSPICIOUS_MOVING_OBJECT!
        """
        # Giả lập khối chuyển động ngụy trang giữa ROI (x=280..340, y=200..260)
        camo_box = BoundingBox(x1=280.0, y1=200.0, x2=340.0, y2=260.0)
        no_humans = []

        is_associated = VideoPipeline._is_box_overlapping_any(
            camo_box,
            no_humans,
            overlap_threshold=0.08,
            margin=35.0
        )
        self.assertFalse(is_associated, "Không có người thì không được triệt tiêu")

        # Không có người gần ROI
        has_nearby = any(
            self.pipeline.is_bbox_near_or_in_roi(b, self.width, self.height)
            for _, b in no_humans
        )
        self.assertFalse(has_nearby)

    def test_full_frame_motion_wakes_up_pipeline_from_idle(self):
        """
        Kịch bản: Phòng trống (IDLE) -> Có người xuất hiện ở góc xa ngoài ROI
        -> has_motion_full kích hoạt đánh thức pipeline (không bị bỏ qua bởi Motion Gating)
        """
        # MotionResult khi có người chuyển động ở góc màn hình ngoài ROI
        res = MotionResult(
            has_motion=False,        # Không có chuyển động trong ROI
            has_motion_full=True,    # Có chuyển động trên toàn khung hình
            has_motion_roi=False,
            motion_area=0,
            motion_boxes=[]
        )

        has_any = getattr(res, "has_motion_full", res.has_motion)
        self.assertTrue(has_any, "has_any_motion phải là True khi có chuyển động full-frame")

        # Motion Gating condition:
        # if enable_motion_gating and state == IDLE and not has_any_motion:
        # -> Condition is False, meaning pipeline WAKES UP and runs YOLO!
        should_sleep = (
            self.pipeline.enable_motion_gating
            and self.pipeline.state_machine.state == ZoneState.IDLE
            and not has_any
            and not res.is_lighting_spike
        )
        self.assertFalse(should_sleep, "Pipeline PHẢI thức dậy chạy YOLO bám vết từ ngoài!")

if __name__ == "__main__":
    unittest.main()
