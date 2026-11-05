import logging
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock
import pytest

from src.core.entity import BoundingBox, FaceDetectionResult
from src.core.frame_analysis_engine import FrameAnalysisEngine


def _make_face_info(
    code: str = "USER001",
    name: str = "Nguyen Van Test",
    is_authorized: bool = True,
    access_source: str = None,
    role: str = "NORMAL_USER",
    reason: str = "Xet quyen"
) -> FaceDetectionResult:
    return FaceDetectionResult(
        bbox=BoundingBox(10, 10, 50, 100),
        score=0.95,
        matched_code=code,
        matched_name=name,
        is_authorized=is_authorized,
        access_source=access_source,
        role=role,
        access_reason=reason,
        access_checked=True
    )


def test_night_rule_allowed_sources():
    """
    Điểm 4, bước C: Ban đêm, các source trong NIGHT_ALLOWED_ACCESS_SOURCES
    (ASSIGNED_PERSONNEL, ACCESS_REQUEST, INCIDENT_RESPONSE, OPEN_EVENT, GUEST_VISIT)
    được phép vào hợp lệ (0 cảnh báo vi phạm).
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_night = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    allowed_sources = [
        "ASSIGNED_PERSONNEL",
        "ACCESS_REQUEST",
        "INCIDENT_RESPONSE",
        "OPEN_EVENT",
        "GUEST_VISIT"
    ]

    for idx, src in enumerate(allowed_sources, start=10):
        face = _make_face_info(code=f"U_{src}", is_authorized=True, access_source=src)
        engine.associate_face(track_id=idx, face_result=face)
        _, alerts, _ = engine.process_frame(
            detected_tracks=[(idx, bbox)],
            camera_code="CAM-001",
            current_time=t_night
        )
        assert len(alerts) == 0, f"Source {src} phai duoc phep ban dem nhung lai bi alert: {alerts}"


def test_night_rule_access_level_triggers_person_intrusion():
    """
    Điểm 4, bước C: Ban đêm, dù is_authorized=True nhưng source='ACCESS_LEVEL'
    (người dùng chỉ có cấp độ chung chứ không có phân công/đơn duyệt) -> Bị cảnh báo PERSON_INTRUSION.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_night = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    face = _make_face_info(code="STU_ACC_LVL", is_authorized=True, access_source="ACCESS_LEVEL")
    engine.associate_face(track_id=20, face_result=face)
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(20, bbox)],
        camera_code="CAM-001",
        current_time=t_night
    )

    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_night_rule_missing_source_none_logs_and_triggers_person_intrusion(caplog):
    """
    Điểm 4, bước C: Thiếu source (source=None) ban đêm -> ghi log cảnh báo (rate-limit)
    và xử lý như ACCESS_LEVEL (phát PERSON_INTRUSION).
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_night = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    face = _make_face_info(code="USER_NO_SRC", is_authorized=True, access_source=None)
    engine.associate_face(track_id=30, face_result=face)

    with caplog.at_level(logging.WARNING):
        _, alerts, _ = engine.process_frame(
            detected_tracks=[(30, bbox)],
            camera_code="CAM-001",
            current_time=t_night
        )

    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"
    assert any("thiếu trường access_source" in record.message or "access_source" in record.message for record in caplog.records)


def test_daytime_rule_access_level_allowed():
    """
    Điểm 4, bước C: Ban ngày, source='ACCESS_LEVEL' và is_authorized=True
    vẫn được lưu thông bình thường (0 cảnh báo vi phạm).
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_day = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    face = _make_face_info(code="STU_DAY", is_authorized=True, access_source="ACCESS_LEVEL")
    engine.associate_face(track_id=40, face_result=face)
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(40, bbox)],
        camera_code="CAM-001",
        current_time=t_day
    )

    assert len(alerts) == 0


def test_daytime_public_area_denied_user_no_alert():
    """
    Điểm 4, bước C: Ban ngày tại khu vực công cộng, người chưa cấp quyền (is_authorized=False)
    vẫn được đi lại bình thường -> KHÔNG cảnh báo UNAUTHORIZED_PERSON.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_day = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    face = _make_face_info(code="GUEST_PUB", is_authorized=False, access_source="NONE")
    engine.associate_face(track_id=50, face_result=face)
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(50, bbox)],
        camera_code="CAM-001",
        current_time=t_day,
        is_public_area=True
    )

    assert len(alerts) == 0


def test_denied_user_at_night_triggers_person_intrusion_regardless_of_source():
    """
    Điểm 4, bước C: Người bị Backend từ chối (is_authorized=False) vào ban đêm
    luôn kích hoạt PERSON_INTRUSION, bất kể role hay source.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_night = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)

    face = _make_face_info(
        code="DENIED_GUARD",
        is_authorized=False,
        access_source="ASSIGNED_PERSONNEL",
        role="GUARD",
        reason="Ca trực đã bị hủy"
    )
    engine.associate_face(track_id=60, face_result=face)
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(60, bbox)],
        camera_code="CAM-001",
        current_time=t_night
    )

    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"
