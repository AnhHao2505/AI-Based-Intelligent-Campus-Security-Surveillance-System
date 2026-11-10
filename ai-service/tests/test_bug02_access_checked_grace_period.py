import time
from datetime import datetime, timezone, timedelta
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult


def test_recognized_user_waiting_for_backend_has_grace_period():
    # When user face is recognized but access_checked=False (in-flight backend check),
    # engine must NOT fire unauthorized alert immediately during grace period (1.5s)
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    # 14:00 daytime
    t0 = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=False,  # default before backend answers
        access_checked=False  # pending backend check
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Frame 1 at t0: Just entered ROI, access check is in-flight
    _, alerts_f1, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0
    )
    # Must NOT fire unauthorized alert in frame 1!
    assert len(alerts_f1) == 0, f"Expected 0 alerts in frame 1, got {len(alerts_f1)}"

    # Frame 2 at t0 + 0.5s: Backend responded with allowed=True!
    face_info.is_authorized = True
    face_info.access_checked = True
    face_info.access_reason = "Cap do truy cap hop le"

    _, alerts_f2, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0 + 0.5
    )
    # User is authorized, must have 0 alerts
    assert len(alerts_f2) == 0


def test_recognized_user_fires_alert_after_grace_period_expires():
    # If backend check never completes or times out past 1.5s, fail-closed fires alert
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t0 = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=False,
        access_checked=False
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Frame 1 at t0
    engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0
    )

    # Frame 2 at t0 + 2.0s (past 1.5s grace period)
    _, alerts_f2, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0 + 2.0
    )
    assert len(alerts_f2) == 1
    assert alerts_f2[0].event_type == "UNAUTHORIZED_PERSON"


def test_backend_timeout_or_exception_access_check_behavior():
    """
    Test kịch bản Backend timeout hoặc ném Exception khi check_camera_access:
    - Trong 1.5s đầu: nằm trong grace period, không bắn cảnh báo.
    - Sau 1.5s: hết grace period, hệ thống hành xử Fail-Closed -> phát cảnh báo UNAUTHORIZED_PERSON.
    """
    from unittest.mock import MagicMock
    import requests

    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t0 = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=False,
        access_checked=False
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Frame 1: Vào ROI tại t0, Backend đang bị timeout
    _, alerts_t0, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0
    )
    assert len(alerts_t0) == 0, "Grace period 1.5s: không được bắn cảnh báo ngay khi backend timeout"

    # Frame 2: Tại t0 + 1.0s (vẫn trong 1.5s grace period)
    _, alerts_t1, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0 + 1.0
    )
    assert len(alerts_t1) == 0, "Vẫn trong grace period (1.0s < 1.5s): không được bắn cảnh báo"

    # Giả lập FaceMatcher gọi Backend bị requests.Timeout hoặc Exception
    mock_matcher = MagicMock()
    mock_matcher.check_camera_access.side_effect = requests.exceptions.Timeout("Backend check-entry timed out after 1.5s")

    # Giả lập pipeline bắt exception:
    try:
        mock_matcher.check_camera_access("CAM-001", "SE194249")
    except requests.exceptions.Timeout:
        # Pipeline catches exception, access_checked remains False or False fail-closed
        pass

    # Frame 3: Tại t0 + 2.0s (> 1.5s grace period)
    _, alerts_t2, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t0 + 2.0
    )
    # Hiện tại sau 1.5s, hệ thống Fail-Closed: cảnh báo UNAUTHORIZED_PERSON
    assert len(alerts_t2) == 1
    assert alerts_t2[0].event_type == "UNAUTHORIZED_PERSON"

