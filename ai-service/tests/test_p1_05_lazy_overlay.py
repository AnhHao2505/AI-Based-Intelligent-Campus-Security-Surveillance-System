import os
import sys
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import BoundingBox, SecurityAlertEvent, TrackedPerson
from src.pipeline.video_pipeline import VideoPipeline
from src.pipeline.stream_worker import CameraStreamWorker


def test_lazy_overlay_skips_drawing_when_not_needed():
    """
    P1-05: Khi need_annotated=False và không có cảnh báo (alerts=[]):
    Pipeline KHÔNG được gọi FrameVisualizer hoặc visualizer.draw_tracked_persons,
    tránh tốn CPU copy và render overlay.
    """
    pipeline = VideoPipeline("CAM-LAZY-TEST")
    pipeline.detector = MagicMock()
    pipeline.face_matcher = MagicMock()
    pipeline.storage_service = MagicMock()
    pipeline.mqtt_client = MagicMock()

    # Giả lập frame 640x480
    frame = np.zeros((480, 640, 3), dtype=np.uint8)

    # Giả lập không có người và không có motion
    pipeline.detector.detect_motion.return_value = MagicMock(
        has_motion=False,
        is_tampering=False,
        motion_boxes=[],
        motion_area=0
    )
    pipeline.state_machine.update = MagicMock(return_value="IDLE")

    with patch("src.pipeline.video_pipeline.FrameVisualizer.draw_all_rois") as mock_draw_rois, \
         patch.object(pipeline.visualizer, "draw_tracked_persons") as mock_draw_persons:

        out_frame, persons, alerts = pipeline.process_frame(frame, current_time=1.0, need_annotated=False)

        assert len(alerts) == 0
        # Không được gọi bất kỳ hàm vẽ nào
        assert mock_draw_rois.call_count == 0, "Không được vẽ ROI khi need_annotated=False và không có cảnh báo"
        assert mock_draw_persons.call_count == 0, "Không được vẽ Person khi need_annotated=False và không có cảnh báo"
        # Frame trả về chính là frame gốc
        assert out_frame is frame


def test_lazy_overlay_draws_once_and_reuses_between_snapshot_and_annotated():
    """
    P1-05: Khi có cảnh báo (alerts phát sinh):
    Pipeline phải render overlay DUY NHẤT 1 LẦN và tái sử dụng cùng frame đã vẽ
    cho cả snapshot cảnh báo (_dispatch_alert_async) và annotated_frame trả về.
    """
    pipeline = VideoPipeline("CAM-LAZY-TEST")
    pipeline.detector = MagicMock()
    pipeline.face_matcher = MagicMock()
    pipeline.storage_service = MagicMock()
    pipeline.mqtt_client = MagicMock()
    pipeline.io_executor = MagicMock()
    pipeline.io_executor._shutdown = False

    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    pipeline.detector.detect_motion.return_value = MagicMock(
        has_motion=True,
        is_tampering=False,
        motion_boxes=[],
        motion_area=1000
    )
    pipeline.detector.detect_and_track.return_value = [
        (1, BoundingBox(10, 10, 100, 200, 0.9))
    ]

    # Giả lập analysis_engine sinh ra 2 cảnh báo cùng lúc trên cùng 1 frame
    fake_alert1 = SecurityAlertEvent(
        camera_code="CAM-LAZY-TEST",
        event_type="UNAUTHORIZED_PERSON",
        track_id=1,
        details="Vi phạm 1"
    )
    fake_alert2 = SecurityAlertEvent(
        camera_code="CAM-LAZY-TEST",
        event_type="AFTER_HOURS_INTRUSION",
        track_id=1,
        details="Vi phạm 2"
    )
    pipeline.analysis_engine.process_frame = MagicMock(
        return_value=([TrackedPerson(track_id=1, bbox=BoundingBox(10, 10, 100, 200, 0.9))], [fake_alert1, fake_alert2], [])
    )

    with patch.object(pipeline, "_dispatch_alert_async") as mock_dispatch, \
         patch("src.pipeline.video_pipeline.FrameVisualizer.draw_all_rois", wraps=lambda f, **kw: f) as mock_draw_rois, \
         patch.object(pipeline.visualizer, "draw_tracked_persons", wraps=lambda f, *args, **kw: f) as mock_draw_persons:

        out_frame, persons, alerts = pipeline.process_frame(frame, current_time=1.0, need_annotated=False)

        assert len(alerts) == 2
        # Vẽ đúng 1 lần cho toàn bộ frame, không vẽ lặp lại cho từng cảnh báo
        assert mock_draw_rois.call_count == 1, f"Kỳ vọng vẽ ROI 1 lần, thực tế: {mock_draw_rois.call_count}"
        assert mock_draw_persons.call_count == 1, f"Kỳ vọng vẽ Person 1 lần, thực tế: {mock_draw_persons.call_count}"

        # Xác nhận _dispatch_alert_async nhận cùng 1 frame tham chiếu với out_frame
        assert pipeline.io_executor.submit.call_count == 2
        # Cả 2 lần submit đều truyền cùng đối tượng snapshot_frame đã render
        call_args1 = pipeline.io_executor.submit.call_args_list[0][0]
        call_args2 = pipeline.io_executor.submit.call_args_list[1][0]
        # call_args: (self._dispatch_alert_async, alert, snapshot_frame)
        assert call_args1[2] is out_frame, "Snapshot alert 1 phải tái dùng chính out_frame đã vẽ"
        assert call_args2[2] is out_frame, "Snapshot alert 2 phải tái dùng chính out_frame đã vẽ"


def test_stream_worker_client_counter_controls_need_annotated():
    """
    P1-05: CameraStreamWorker quản lý bộ đếm client xem live-annotated:
    - Khi có client (count > 0): has_active_client = True
    - Khi không có client (count == 0): has_active_client = False
    """
    worker = CameraStreamWorker(camera_code="CAM-LAZY-WORKER", rtsp_url="rtsp://dummy/test")
    assert hasattr(worker, "has_active_client"), "Worker phải có thuộc tính has_active_client"
    assert worker.has_active_client is False

    worker.add_client()
    assert worker.has_active_client is True

    worker.add_client()
    assert worker.has_active_client is True

    worker.remove_client()
    assert worker.has_active_client is True

    worker.remove_client()
    assert worker.has_active_client is False

    # Không bị âm
    worker.remove_client()
    assert worker.has_active_client is False
