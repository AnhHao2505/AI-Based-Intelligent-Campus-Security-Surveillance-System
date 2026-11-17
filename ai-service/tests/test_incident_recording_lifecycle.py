import os
import sys
from unittest.mock import MagicMock, patch
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import SecurityAlertEvent, TrackedPerson, BoundingBox
from src.pipeline.video_pipeline import VideoPipeline


@pytest.fixture
def mocked_pipeline():
    with patch("src.pipeline.video_pipeline.HumanDetector"), \
         patch("src.pipeline.video_pipeline.FaceDetector"), \
         patch("src.pipeline.video_pipeline.FaceMatcher"), \
         patch("src.pipeline.video_pipeline.FrameAnalysisEngine"), \
         patch("src.pipeline.video_pipeline.MotionDetector"), \
         patch("src.pipeline.video_pipeline.ZoneStateMachine"), \
         patch("src.pipeline.video_pipeline.get_security_mqtt_client"), \
         patch("src.pipeline.video_pipeline.StorageService"), \
         patch("src.pipeline.video_pipeline.FrameVisualizer"):
        pipeline = VideoPipeline("CAM-TEST-INCIDENT")
        pipeline.incident_start_delay_seconds = 2.0
        pipeline.incident_no_detection_timeout_seconds = 2.0
        pipeline.analysis_engine.max_inactive_seconds = 10.0
        yield pipeline


def test_incident_recording_starts_2_seconds_after_first_detection(mocked_pipeline):
    """
    Quy tắc 1: Lần phát hiện đầu tiên ở t=10.0:
    - Tại t=10.0: Chưa bắt đầu ghi hình (chờ 2s)
    - Tại t=11.5: Vẫn chưa đủ 2s (chưa bắt đầu)
    - Tại t=12.0: Đủ 2.0s kể từ lần phát hiện đầu tiên -> Bắt đầu ghi hình sự cố!
    """
    pipeline = mocked_pipeline

    with patch("src.pipeline.video_pipeline.incident_recording_manager") as mock_manager:
        mock_manager.is_recording.return_value = False

        alert = SecurityAlertEvent(
            event_id="EVT-TEST-001",
            camera_code="CAM-TEST-INCIDENT",
            event_type="UNAUTHORIZED_PERSON",
            details="Người không có quyền trong khu vực",
            track_id=1,
        )

        dummy_frame = MagicMock()
        dummy_frame.shape = (480, 640, 3)

        pipeline.human_detector.detect_and_track.return_value = []
        pipeline.motion_detector.detect_motion.return_value = MagicMock(motion_boxes=[], has_motion=False)
        pipeline.state_machine.update.return_value = "ALERT"
        pipeline.analysis_engine.process_frame.return_value = ([], [alert], [])
        pipeline.analysis_engine.active_tracks = {}

        # 1. Khung hình tại t=10.0 (phát hiện đầu tiên)
        pipeline.process_frame(dummy_frame, current_time=10.0)
        assert pipeline._incident_first_detected_at == 10.0
        mock_manager.start_incident_recording.assert_not_called()

        # 2. Khung hình tại t=11.5 (1.5s sau phát hiện đầu - chưa đủ 2s)
        pipeline.analysis_engine.process_frame.return_value = ([], [], [])
        violator = TrackedPerson(
            track_id=1,
            bbox=BoundingBox(10, 10, 50, 50),
            last_seen_time=11.5,
            security_display_state="UNAUTHORIZED",
            is_in_roi=True,
        )
        pipeline.analysis_engine.active_tracks = {1: violator}
        pipeline.process_frame(dummy_frame, current_time=11.5)
        mock_manager.start_incident_recording.assert_not_called()

        # 3. Khung hình tại t=12.0 (đủ 2.0s kể từ t=10.0)
        violator.last_seen_time = 12.0
        pipeline.process_frame(dummy_frame, current_time=12.0)
        mock_manager.start_incident_recording.assert_called_once()
        call_kwargs = mock_manager.start_incident_recording.call_args[1]
        assert call_kwargs["incident_id"] == "EVT-TEST-001"
        assert call_kwargs["camera_code"] == "CAM-TEST-INCIDENT"
        assert call_kwargs["event_type"] == "UNAUTHORIZED_PERSON"


def test_incident_recording_cancelled_if_detection_ceases_before_2_seconds(mocked_pipeline):
    """
    Nếu phát hiện biến mất trước khi đủ 2 giây (glitch/nhiễu 0.5s):
    Không bắt đầu ghi hình, huỷ bỏ đếm ngược.
    """
    pipeline = mocked_pipeline

    with patch("src.pipeline.video_pipeline.incident_recording_manager") as mock_manager:
        mock_manager.is_recording.return_value = False

        alert = SecurityAlertEvent(
            event_id="EVT-GLITCH",
            camera_code="CAM-TEST-INCIDENT",
            event_type="SUSPICIOUS_MOVING_OBJECT",
            details="Chuyển động thoáng qua",
            track_id=-1,
        )
        dummy_frame = MagicMock()
        dummy_frame.shape = (480, 640, 3)

        pipeline.human_detector.detect_and_track.return_value = []
        pipeline.motion_detector.detect_motion.return_value = MagicMock(motion_boxes=[], has_motion=False)
        pipeline.state_machine.update.return_value = "IDLE"
        pipeline.analysis_engine.active_tracks = {}

        # Phát hiện tại t=5.0
        pipeline.analysis_engine.process_frame.return_value = ([], [alert], [])
        pipeline.process_frame(dummy_frame, current_time=5.0)
        assert pipeline._incident_first_detected_at == 5.0

        # Tại t=6.0: Biến mất, không còn alert và không còn active_tracks
        pipeline.analysis_engine.process_frame.return_value = ([], [], [])
        pipeline.process_frame(dummy_frame, current_time=6.0)

        # Đếm ngược đã được reset
        assert pipeline._incident_first_detected_at is None
        mock_manager.start_incident_recording.assert_not_called()


def test_incident_recording_ends_when_no_detection_anymore(mocked_pipeline):
    """
    Quy tắc 2: Đang ghi hình sự cố, khi đối tượng rời khỏi tầm nhìn camera
    (không còn detection >= 2.0s):
    Tự động dừng ghi hình sự cố và chốt file video MinIO.
    """
    pipeline = mocked_pipeline

    with patch("src.pipeline.video_pipeline.incident_recording_manager") as mock_manager:
        mock_manager.is_recording.return_value = True
        pipeline._incident_recording_active = True
        pipeline._incident_first_detected_at = 10.0
        pipeline._incident_last_detected_at = 20.0

        dummy_frame = MagicMock()
        dummy_frame.shape = (480, 640, 3)

        pipeline.human_detector.detect_and_track.return_value = []
        pipeline.motion_detector.detect_motion.return_value = MagicMock(motion_boxes=[], has_motion=False)
        pipeline.state_machine.update.return_value = "IDLE"
        pipeline.analysis_engine.process_frame.return_value = ([], [], [])
        pipeline.analysis_engine.active_tracks = {}

        # Khung hình tại t=21.0 (mới 1.0s không có detection -> vẫn tiếp tục ghi)
        pipeline.process_frame(dummy_frame, current_time=21.0)
        mock_manager.stop_camera_recordings.assert_not_called()

        # Khung hình tại t=22.0 (đủ 2.0s không còn phát hiện -> kết thúc ghi hình!)
        pipeline.process_frame(dummy_frame, current_time=22.0)
        mock_manager.stop_camera_recordings.assert_called_once_with("CAM-TEST-INCIDENT")
        assert not pipeline._incident_recording_active
        assert pipeline._incident_first_detected_at is None
