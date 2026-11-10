"""
L1 / L4 (06/11/2026): luật ban đêm xét theo nguồn cấp quyền (access_source) backend trả, lý do từ chối khách theo mã.
BR-294: sau AI_AFTER_HOUR_START chỉ miễn ASSIGNED_PERSONNEL, ACCESS_REQUEST, GUEST_VISIT, INCIDENT_RESPONSE hoặc GUARD.
"""
import os
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import BoundingBox, Point, FaceDetectionResult
from src.core.frame_analysis_engine import FrameAnalysisEngine

TZ = ZoneInfo("Asia/Ho_Chi_Minh")
ROI = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
BBOX = BoundingBox(x1=200, y1=200, x2=300, y2=300)


def _at(hour: int) -> float:
    return datetime.now(TZ).replace(hour=hour, minute=0, second=0, microsecond=0).timestamp()


AFTER_HOURS_TIME = _at(20)  # 20:00, trong khung đêm 18:00 -> 06:00
DAYTIME = _at(10)           # 10:00, ngoài khung đêm


def _run(current_time, is_authorized, access_source, access_reason, role="NORMAL_USER", access_deny_reason=None):
    engine = FrameAnalysisEngine(after_hour_start="18:00", after_hour_end="06:00")
    face = FaceDetectionResult(
        bbox=BBOX,
        score=0.95,
        matched_code="X001",
        matched_name="Người Thử",
        is_authorized=is_authorized,
        is_active=True,
        access_reason=access_reason,
        area_name="Phòng Lab 01",
        role=role,
        access_checked=True,
        access_source=access_source,
        access_deny_reason=access_deny_reason,
    )
    engine.associate_face(track_id=1, face_result=face)
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, BBOX)],
        roi_polygon=ROI,
        camera_code="CAM-TEST",
        current_time=current_time,
    )
    return alerts


@pytest.mark.parametrize("source, reason", [
    ("GUEST_VISIT", "Khách tham quan hợp lệ trong lượt tiếp khách"),
    ("ACCESS_REQUEST", "Có đơn xin quyền đã được duyệt trong khung thời gian"),
    ("ASSIGNED_PERSONNEL", "Người dùng được gán vào khu vực"),
    ("INCIDENT_RESPONSE", "Bảo vệ đang xử lý sự cố tại khu vực"),
])
def test_after_hours_allowed_exempt_source_no_alert(source, reason):
    alerts = _run(AFTER_HOURS_TIME, True, source, reason)
    assert alerts == []


def test_after_hours_guest_visit_no_alert_even_without_keyword():
    # Trước khi sửa: lý do của khách không chứa từ khoá nào -> PERSON_INTRUSION (L1)
    alerts = _run(AFTER_HOURS_TIME, True, "GUEST_VISIT", "[VÀO ROI: Cửa] Khách tham quan hợp lệ trong lượt tiếp khách")
    assert alerts == []


@pytest.mark.parametrize("source, reason", [
    ("ACCESS_LEVEL", "Cấp độ truy cập của người dùng phù hợp với khu vực"),
    ("OPEN_EVENT", "Khu vực đang mở chế độ sự kiện cho thành viên"),
])
def test_after_hours_allowed_non_exempt_source_intrusion(source, reason):
    alerts = _run(AFTER_HOURS_TIME, True, source, reason)
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_access_level_with_keyword_still_intrusion():
    # Có source thì không dò chữ: lý do chứa "gán" nhưng nguồn là ACCESS_LEVEL -> vẫn PERSON_INTRUSION
    alerts = _run(AFTER_HOURS_TIME, True, "ACCESS_LEVEL", "Người dùng được gán vào khu vực")
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_guard_source_none_no_alert():
    alerts = _run(AFTER_HOURS_TIME, True, None, "Bảo vệ trực ca", role="GUARD")
    assert alerts == []


def test_after_hours_source_missing_falls_back_to_keyword():
    # K4: ban đêm thiếu access_source -> từ chối (PERSON_INTRUSION), không dò chữ trong lý do
    alerts = _run(AFTER_HOURS_TIME, True, None, "Có đơn xin quyền đã được duyệt trong khung thời gian")
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_source_missing_without_keyword_intrusion():
    alerts = _run(AFTER_HOURS_TIME, True, None, "Khách tham quan hợp lệ trong lượt tiếp khách")
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_open_event_source_intrusion():
    # 1) ban đêm, source OPEN_EVENT → PERSON_INTRUSION
    alerts = _run(AFTER_HOURS_TIME, True, "OPEN_EVENT", "Khu vực đang mở chế độ sự kiện cho thành viên")
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_guard_source_none_explicit_no_alert():
    # 2) ban đêm, role GUARD, source None → không cảnh báo
    alerts = _run(AFTER_HOURS_TIME, True, None, "Bảo vệ trong ca trực", role="GUARD")
    assert alerts == []


def test_daytime_allowed_access_level_no_alert():
    alerts = _run(DAYTIME, True, "ACCESS_LEVEL", "Cấp độ truy cập của người dùng phù hợp với khu vực")
    assert alerts == []


def test_daytime_guest_denied_area_not_in_visit_reason_by_code():
    alerts = _run(DAYTIME, False, "NONE", "Khu vực này không nằm trong danh sách tiếp đón của khách",
                  access_deny_reason="AREA_NOT_IN_VISIT")
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNAUTHORIZED_PERSON"
    assert "Lý do: Khu vực không thuộc lượt khách" in alerts[0].details
    assert "Ngoài giờ tiếp khách" not in alerts[0].details


def test_daytime_guest_denied_unknown_code_generic_reason():
    alerts = _run(DAYTIME, False, "NONE", "Khách không có quyền vào khu vực này", access_deny_reason="SOMETHING_NEW")
    assert len(alerts) == 1
    assert "Lý do: Không có quyền" in alerts[0].details


def test_daytime_guest_denied_without_code_keeps_keyword_reason():
    # Không có access_deny_reason -> chuỗi elif dò chữ cũ giữ nguyên
    alerts = _run(DAYTIME, False, "NONE", "Khách vào ngoài khung giờ được tiếp đón")
    assert len(alerts) == 1
    assert "Lý do: Ngoài giờ tiếp khách" in alerts[0].details
