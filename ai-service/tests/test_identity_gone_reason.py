"""
Lý do từ chối "Camera không tồn tại" / "Khu vực không tồn tại" (camera hoặc khu vực đã bị xoá khỏi DB nhưng AI vẫn chạy)
KHÔNG được coi là người dùng không tồn tại: không xoá khuôn mặt khỏi Face RAM Cache, không gỡ danh tính khỏi track,
không đổi cảnh báo thành UNKNOWN_PERSON. Chỉ "Người dùng không tồn tại" và dữ liệu sinh trắc khách đã bị xoá mới là mất danh tính.
"""
import os
import sys
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.access_reasons import is_identity_gone_reason
from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.core.frame_analysis_engine import FrameAnalysisEngine

DAY_TS = 1795057200.0  # 19/11/2026 10:00 +07:00
BBOX = BoundingBox(x1=200, y1=200, x2=300, y2=300)
ROI = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]


@pytest.mark.parametrize("reason, deny, expected", [
    ("Người dùng không tồn tại", None, True),
    ("[VÀO ROI: Vùng giám sát 1] Người dùng không tồn tại", None, True),
    ("[VÀO ROI: Cổng] Dữ liệu sinh trắc học của khách đã bị xoá", None, True),
    ("Dữ liệu sinh trắc học của khách đã bị xóa", None, True),
    ("Khách không có quyền vào khu vực này", "BIOMETRIC_DELETED", True),
    ("Camera không tồn tại", None, False),
    ("Khu vực không tồn tại", None, False),
    ("Camera đã ngừng hoạt động (DECOMMISSIONED)", None, False),
    ("[VÀO ROI: KT Điện] Cấp độ truy cập không đủ (Khu vực Cấp 3, người dùng Cấp 2)", None, False),
    ("Lượt tiếp khách chưa được duyệt hoặc không còn hiệu lực", "VISIT_NOT_ACTIVE", False),
    (None, None, False),
    ("", None, False),
])
def test_is_identity_gone_reason(reason, deny, expected):
    assert is_identity_gone_reason(reason, deny) is expected


def _matcher_with_backend_reply(reason, deny_reason=None):
    from src.core.face_matcher import FaceMatcher

    with patch("psycopg2.connect", side_effect=Exception("DB offline for testing")):
        matcher = FaceMatcher()
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = {
        "success": True,
        "data": {"allowed": False, "source": "NONE", "reason": reason, "denyReason": deny_reason},
    }
    return matcher, resp


def test_camera_not_found_does_not_evict_face_from_cache():
    matcher, resp = _matcher_with_backend_reply("Camera không tồn tại")
    with patch("requests.post", return_value=resp), patch.object(matcher.cache, "remove_face") as remove_face:
        allowed, reason, *_ = matcher.check_camera_access("CAM-002", "SE198765")
    assert allowed is False
    assert reason == "Camera không tồn tại"
    remove_face.assert_not_called()


def test_area_not_found_does_not_evict_face_from_cache():
    matcher, resp = _matcher_with_backend_reply("Khu vực không tồn tại")
    with patch("requests.post", return_value=resp), patch.object(matcher.cache, "remove_face") as remove_face:
        matcher.check_camera_access("CAM-007", "SE198765")
    remove_face.assert_not_called()


def test_user_not_found_still_evicts_face_from_cache():
    matcher, resp = _matcher_with_backend_reply("[VÀO ROI: Vùng giám sát 1] Người dùng không tồn tại")
    with patch("requests.post", return_value=resp), patch.object(matcher.cache, "remove_face") as remove_face:
        matcher.check_camera_access("CAM-007", "SE198765")
    remove_face.assert_called_once_with("SE198765")


def _bare_pipeline():
    from src.pipeline.video_pipeline import VideoPipeline

    pipeline = VideoPipeline.__new__(VideoPipeline)
    pipeline.camera_code = "CAM-TEST"
    pipeline.analysis_engine = MagicMock()
    pipeline.analysis_engine.active_tracks = {}
    pipeline._record_access_checked = MagicMock()
    return pipeline


def _face(reason=None):
    return FaceDetectionResult(
        bbox=BBOX, score=0.9, matched_code="SE198765", matched_name="Phan Gia Anh",
        is_authorized=False, is_active=True, access_reason=reason, access_checked=reason is not None,
    )


def test_pipeline_keeps_identity_when_camera_not_found():
    pipeline = _bare_pipeline()
    face = _face()
    pipeline._apply_access_decision(face, False, "Camera không tồn tại", None, track_id=1)
    assert face.matched_code == "SE198765"
    assert face.matched_name == "Phan Gia Anh"
    assert face.access_reason == "Camera không tồn tại"


def test_pipeline_clears_identity_when_user_not_found():
    pipeline = _bare_pipeline()
    face = _face()
    pipeline._apply_access_decision(face, False, "Người dùng không tồn tại", None, track_id=1)
    assert face.matched_code is None
    assert face.access_reason == "Đối tượng không xác định"


def test_engine_camera_not_found_is_unauthorized_not_unknown():
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    engine.associate_face(track_id=8, face_result=_face("Camera không tồn tại"))

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(8, BBOX)], roi_polygon=ROI, camera_code="CAM-002", current_time=DAY_TS
    )

    assert len(alerts) == 1
    assert alerts[0].event_type == "UNAUTHORIZED_PERSON"
    assert "SE198765" in alerts[0].details
