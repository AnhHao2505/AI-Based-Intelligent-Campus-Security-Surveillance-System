import time
from datetime import datetime, timezone, timedelta
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult


def test_denied_user_with_word_gan_is_not_treated_as_guard_at_night():
    # User denied with reason "Chưa được gán quyền truy cập" must NOT be treated as guard
    # and MUST trigger PERSON_INTRUSION at night
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="STU001",
        matched_name="Nguyen Van A",
        is_authorized=False,  # Backend denied!
        access_reason="Chưa được gán quyền truy cập vào phòng máy chủ",
        role="STUDENT",
        access_checked=True
    )
    engine.associate_face(track_id=1, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    # Must generate PERSON_INTRUSION alert, NOT treat as authorized guard!
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"
    assert "Nguyen Van A" in alerts[0].details


def test_guard_with_authorized_shift_is_allowed_at_night():
    # Legitimate guard with is_authorized=True must be allowed at night without alert
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="GRD001",
        matched_name="Bao Ve Tran Van B",
        is_authorized=True,
        access_source="ASSIGNED_PERSONNEL",
        access_reason="Phân công ca trực đêm hợp lệ",
        role="GUARD",
        access_checked=True
    )
    engine.associate_face(track_id=2, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(2, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    # Valid guard on duty should have 0 violation alerts
    assert len(alerts) == 0


def test_guard_denied_by_backend_is_not_allowed_at_night():
    # If Backend says is_authorized=False (e.g. guard off duty or suspended),
    # AI must NOT fail-open and override the denial
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="GRD002",
        matched_name="Bao Ve Le Van C",
        is_authorized=False,
        access_reason="Bảo vệ không có ca trực trong khung giờ này",
        role="GUARD",
        access_checked=True
    )
    engine.associate_face(track_id=3, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(3, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_approved_overtime_request_is_allowed_at_night():
    # User with approved overtime request (Backend is_authorized=True, reason has "ngoài giờ")
    # must be allowed at night without alert
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="STU002",
        matched_name="Tran Thi Overtime",
        is_authorized=True,
        access_source="ACCESS_REQUEST",
        access_reason="Đã duyệt đơn đăng ký làm việc ngoài giờ tại phòng nghiên cứu",
        role="NORMAL_USER",
        access_checked=True
    )
    engine.associate_face(track_id=4, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(4, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    # Approved overtime user must have 0 alerts at night
    assert len(alerts) == 0


def test_denied_user_with_reason_containing_both_gan_and_bao_ve_still_alerted():
    # Denied user whose reason contains both "gán" and "bảo vệ" must be alerted
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="USER003",
        matched_name="Nguyen Van Denied",
        is_authorized=False,
        access_reason="Chưa gán ca trực bảo vệ cho tài khoản này",
        role="GUARD",
        access_checked=True
    )
    engine.associate_face(track_id=5, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(5, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_current_behavior_normal_user_authorized_no_keywords_at_night():
    """
    Điểm 4, bước A: Mô tả hành vi HIỆN TẠI cho người dùng thường (NORMAL_USER),
    is_authorized=True (ví dụ do accessLevel đủ cao hoặc Backend duyệt), ban đêm,
    reason không chứa từ khóa đặc biệt ("Cấp độ truy cập của người dùng phù hợp với khu vực").
    Kết quả: Phát cảnh báo PERSON_INTRUSION vì role không phải GUARD và reason không có từ khóa đêm.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="STU999",
        matched_name="Nguyen Van Thuong",
        is_authorized=True,
        access_reason="Cấp độ truy cập của người dùng phù hợp với khu vực",
        role="NORMAL_USER",
        access_checked=True
    )
    engine.associate_face(track_id=99, face_result=face_info)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(99, bbox)],
        camera_code="CAM-001",
        current_time=t_23h
    )

    # Hành vi hiện tại: sinh đúng 1 cảnh báo PERSON_INTRUSION
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"
    assert "Nguyen Van Thuong" in alerts[0].details
