import unittest
import numpy as np
from src.core.entity import BoundingBox, Point, RoiPolygonConfig, TrackedPerson, MotionResult
from src.pipeline.video_pipeline import VideoPipeline
from src.utils.visualizer import FrameVisualizer


class TestMotionSuppressionInHumanBoxes(unittest.TestCase):
    """
    Kiểm thử cơ chế:
    1. Phát hiện chuyển động trước (OpenCV MOG2)
    2. YOLO detect người và vẽ bounding box
    3. Trong bounding box người do YOLO vẽ, các box chuyển động (motion detection) bị triệt tiêu 100%
       để tránh vẽ bounding box vàng dư thừa, chập chờn lên cơ thể người.
    4. Chỉ giữ lại và cảnh báo SUSPICIOUS_MOTION nếu có vật thể lạ/ngụy trang nằm ngoài người.
    """

    def test_motion_box_overlapping_human_suppression(self):
        # Giả lập 1 người được phát hiện ở tọa độ [100, 100, 300, 500] (w=200, h=400)
        human_box = BoundingBox(x1=100.0, y1=100.0, x2=300.0, y2=500.0, confidence=0.9)
        human_bboxes = [human_box]

        # 1. Motion box 1: Nằm hoàn toàn bên trong cơ thể người (chuyển động của tay/chân)
        mb_inside = BoundingBox(x1=150.0, y1=200.0, x2=250.0, y2=350.0)
        self.assertTrue(
            VideoPipeline._is_box_overlapping_any(mb_inside, human_bboxes),
            "Chuyển động bên trong bounding box người phải được nhận diện để triệt tiêu"
        )

        # 2. Motion box 2: Giao cắt đáng kể (>15% diện tích) với cơ thể người
        mb_overlapping = BoundingBox(x1=80.0, y1=150.0, x2=150.0, y2=250.0) # w=70, h=100, inter=[100,150,150,250] -> 50*100=5000 / 7000 = 71%
        self.assertTrue(
            VideoPipeline._is_box_overlapping_any(mb_overlapping, human_bboxes),
            "Chuyển động giao cắt với cơ thể người phải được triệt tiêu"
        )

        # 3. Motion box 3: Nằm xa người (vật thể lạ riêng biệt hoặc kẻ bò trườn ở góc khác)
        mb_separate = BoundingBox(x1=600.0, y1=400.0, x2=700.0, y2=480.0)
        self.assertFalse(
            VideoPipeline._is_box_overlapping_any(mb_separate, human_bboxes),
            "Vật thể chuyển động riêng biệt không bị ảnh hưởng bởi người"
        )

    def test_filter_motion_boxes_retains_only_intruder_motion(self):
        human_bboxes = [BoundingBox(x1=200.0, y1=100.0, x2=350.0, y2=450.0)]

        all_motion_boxes = [
            BoundingBox(x1=220.0, y1=150.0, x2=320.0, y2=300.0),  # Thuộc về người
            BoundingBox(x1=205.0, y1=350.0, x2=280.0, y2=440.0),  # Thuộc về chân người
            BoundingBox(x1=50.0, y1=50.0, x2=120.0, y2=100.0),    # Kẻ bò trườn / ngụy trang bên góc trái
        ]

        # Sau khi lọc bằng helper
        filtered = [
            mb for mb in all_motion_boxes
            if not VideoPipeline._is_box_overlapping_any(mb, human_bboxes)
        ]

        self.assertEqual(len(filtered), 1, "Chỉ được giữ lại 1 motion box duy nhất của vật thể ngụy trang")
        self.assertEqual(filtered[0].x1, 50.0)
        self.assertEqual(filtered[0].y1, 50.0)

    def test_ghost_boxes_suppression_in_visualizer(self):
        # Kiểm tra visualizer không vẽ người bị biến mất (is_currently_visible = False)
        canvas = np.zeros((480, 640, 3), dtype=np.uint8)
        p_active = TrackedPerson(
            track_id=1,
            bbox=BoundingBox(x1=100, y1=100, x2=200, y2=300),
            first_seen_time=100.0,
            last_seen_time=100.0
        )
        p_active.is_currently_visible = True

        p_ghost = TrackedPerson(
            track_id=2,
            bbox=BoundingBox(x1=400, y1=100, x2=500, y2=300),
            first_seen_time=98.0,
            last_seen_time=98.0
        )
        p_ghost.is_currently_visible = False

        vis = FrameVisualizer()
        drawn = vis.draw_tracked_persons(canvas.copy(), [p_active, p_ghost])

        # Vùng của p_active (x=100..200, y=100..300) phải có pixel được vẽ (khác 0)
        active_crop = drawn[100:300, 100:200]
        self.assertTrue(np.any(active_crop > 0), "Người đang xuất hiện phải được vẽ Bounding Box")

        # Vùng của p_ghost (x=400..500, y=100..300) phải hoàn toàn sạch bóng (bằng 0)
        ghost_crop = drawn[100:300, 400:500]
        self.assertTrue(np.all(ghost_crop == 0), "Ghost box (người đã rời khung hình) không được phép vẽ lên màn hình")


if __name__ == "__main__":
    unittest.main()
