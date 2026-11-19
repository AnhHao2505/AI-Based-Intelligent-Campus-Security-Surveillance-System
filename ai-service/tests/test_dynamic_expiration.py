import os
import sys
import time
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from src.core.entity import TrackedPerson, BoundingBox, FaceDetectionResult, Point
from src.core.frame_analysis_engine import FrameAnalysisEngine

FULL_ROI = [Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)]
# ROI ở góc dưới phải, không chứa bbox (0.1–0.2): dùng cho trạng thái sticky (đã qua ROI, đang ở trong phòng)
FAR_ROI = [Point(0.7, 0.7), Point(0.9, 0.7), Point(0.9, 0.9), Point(0.7, 0.9)]
BBOX = BoundingBox(0.1, 0.1, 0.2, 0.2)


def _person(track_id, code, name, authorized, reason=None, starts_at=None, expires_at=None, state="AUTHORIZED"):
    p = TrackedPerson(track_id=track_id, bbox=BBOX)
    p.face_detected = True
    p.face_info = FaceDetectionResult(
        bbox=BBOX,
        score=0.92,
        matched_code=code,
        matched_name=name,
        is_authorized=authorized,
        access_checked=True,
        access_reason=reason,
        starts_at=starts_at,
        expires_at=expires_at,
    )
    p.starts_at = starts_at
    p.expires_at = expires_at
    p.security_display_state = state
    return p


def _backend_result(person, allowed, reason, source=None, expires_at=None, starts_at=None):
    """Giả lập VideoPipeline._async_record_roi_access áp kết quả backend của lượt hỏi lại."""
    person.face_info.is_authorized = allowed
    person.face_info.access_reason = reason
    person.face_info.access_source = source
    person.face_info.expires_at = expires_at
    person.face_info.starts_at = starts_at
    person.expires_at = expires_at
    person.starts_at = starts_at
    person.window_recheck_result_ready = True


def test_expires_at_reached_only_rechecks_backend_no_local_alert():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(1, "SE198765", "Phan Gia Anh", True, expires_at=t + 10.0)
    p.is_in_roi = True
    engine.active_tracks[1] = p

    tracks, alerts, events = engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert len(alerts) == 0

    # Chạm mốc hết hạn: AI KHÔNG tự đổi đỏ, KHÔNG phát cảnh báo; chỉ gửi 1 lượt ENTRY hỏi lại backend
    tracks, alerts, events = engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.033)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert tracks[0].face_info.is_authorized is True
    assert len(alerts) == 0
    rechecks = [e for e in events if e.get("force_recheck")]
    assert len(rechecks) == 1
    assert rechecks[0]["user_code"] == "SE198765"
    assert rechecks[0]["direction"] == "ENTRY"
    assert rechecks[0]["track_id"] == 1

    # Frame kế tiếp: chưa có kết quả backend -> không gửi lặp
    _, alerts, events = engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.066)
    assert not [e for e in events if e.get("force_recheck")]
    assert len(alerts) == 0


def test_expires_at_backend_confirms_denied_emits_alert_despite_old_cooldown():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(1, "SE198765", "Phan Gia Anh", True, expires_at=t + 10.0)
    p.is_in_roi = True
    # Từng bị cảnh báo vi phạm cách đây 5s (cooldown còn hiệu lực)
    p.alert_unauthorized_sent = True
    engine.track_last_unauthorized_alert_time[1] = t - 5.0
    engine.user_last_unauthorized_alert_time["SE198765"] = t - 5.0
    engine.active_tracks[1] = p

    engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.0)
    _backend_result(p, False, "Đơn xin quyền đã hết hạn sử dụng trong ngày (Đã kết thúc lúc: 17:53 18/11/2026)", "NONE")

    tracks, alerts, _ = engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.2)
    assert tracks[0].security_display_state == "UNAUTHORIZED"
    unauth = [a for a in alerts if a.event_type == "UNAUTHORIZED_PERSON"]
    assert len(unauth) == 1
    assert "SE198765" in unauth[0].details
    assert "Hết hạn đơn duyệt" in unauth[0].details


def test_expires_at_backend_still_allows_overlapping_source_no_false_alarm():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(1, "SE198765", "Phan Gia Anh", True, expires_at=t + 10.0)
    p.is_in_roi = True
    engine.active_tracks[1] = p

    engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.0)
    # Còn đơn khác phủ tiếp -> backend vẫn cho phép, kèm mốc hết hạn mới
    _backend_result(p, True, "Có đơn xin quyền đã được duyệt trong khung thời gian", "ACCESS_REQUEST", expires_at=t + 3600.0)

    tracks, alerts, _ = engine.process_frame([(1, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.2)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert not [a for a in alerts if a.event_type in ("UNAUTHORIZED_PERSON", "PERSON_INTRUSION")]
    assert tracks[0].expires_at == t + 3600.0


def test_starts_at_reached_waits_for_backend_then_authorizes():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(
        2, "SE170123", "Nguyen Van Minh", False,
        reason="Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 17:52 18/11/2026)",
        starts_at=t + 10.0, state="UNAUTHORIZED",
    )
    p.is_in_roi = True
    p.alert_unauthorized_sent = True
    engine.track_last_unauthorized_alert_time[2] = t
    engine.user_last_unauthorized_alert_time["SE170123"] = t
    engine.active_tracks[2] = p

    # Chạm starts_at: chưa tự chuyển xanh, chỉ gửi lượt hỏi lại; không phát cảnh báo vi phạm mới
    tracks, alerts, events = engine.process_frame([(2, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.033)
    assert tracks[0].face_info.is_authorized is False
    assert len([e for e in events if e.get("force_recheck")]) == 1
    assert not [a for a in alerts if a.event_type == "UNAUTHORIZED_PERSON"]

    # Backend xác nhận cho phép (nguồn thật do backend trả, không ghi cứng ACCESS_REQUEST)
    _backend_result(p, True, "Khu vực trong khung lịch sự kiện", "OPEN_EVENT", expires_at=t + 3600.0)
    tracks, alerts, _ = engine.process_frame([(2, BBOX)], roi_polygon=FULL_ROI, current_time=t + 10.2)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert tracks[0].face_info.access_source == "OPEN_EVENT"
    assert not [a for a in alerts if a.event_type == "UNAUTHORIZED_PERSON"]


def test_sticky_state_expiry_confirmed_by_backend_turns_red_with_alert():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(3, "SE198765", "Phan Gia Anh", True, expires_at=t + 10.0)
    p.has_entered_roi = True
    p.entered_roi_label = "Cửa phòng Lab"
    engine.active_tracks[3] = p

    tracks, alerts, events = engine.process_frame([(3, BBOX)], roi_polygon=FAR_ROI, current_time=t + 10.0)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert len(alerts) == 0
    assert len([e for e in events if e.get("force_recheck")]) == 1

    _backend_result(p, False, "Đơn xin quyền đã hết hạn sử dụng trong ngày (Đã kết thúc lúc: 17:53 18/11/2026)", "NONE")
    tracks, alerts, _ = engine.process_frame([(3, BBOX)], roi_polygon=FAR_ROI, current_time=t + 10.2)
    assert tracks[0].security_display_state == "UNAUTHORIZED"
    unauth = [a for a in alerts if a.event_type == "UNAUTHORIZED_PERSON"]
    assert len(unauth) == 1
    assert "Hết hạn đơn duyệt" in unauth[0].details


def test_sticky_state_starts_at_confirmed_by_backend_turns_green():
    engine = FrameAnalysisEngine()
    t = 1000.0
    p = _person(
        4, "SE170123", "Nguyen Van Minh", False,
        reason="Chưa tới khung giờ được cấp phép theo đơn", starts_at=t + 10.0, state="UNAUTHORIZED",
    )
    p.has_entered_roi = True
    p.entered_roi_label = "Cửa phòng Lab"
    engine.active_tracks[4] = p

    engine.process_frame([(4, BBOX)], roi_polygon=FAR_ROI, current_time=t + 10.0)
    _backend_result(p, True, "Có đơn xin quyền đã được duyệt trong khung thời gian", "ACCESS_REQUEST", expires_at=t + 60.0)
    tracks, alerts, _ = engine.process_frame([(4, BBOX)], roi_polygon=FAR_ROI, current_time=t + 10.2)
    assert tracks[0].security_display_state == "AUTHORIZED"
    assert not [a for a in alerts if a.event_type == "UNAUTHORIZED_PERSON"]


def test_face_matcher_denial_cache_ttl_capped_by_starts_at():
    from unittest.mock import MagicMock, patch
    from src.core.face_matcher import FaceMatcher

    matcher = FaceMatcher()
    now = 2000.0
    # starts_at là 3s sau (trong khi deny_ttl mặc định là 5s)
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "code": 200,
        "data": {
            "allowed": False,
            "reason": "Chưa tới khung giờ được cấp phép theo đơn",
            "startsAt": now + 3.0,
            "expiresAt": None
        }
    }

    with patch("time.time", return_value=now):
        with patch("requests.post", return_value=mock_resp):
            res = matcher.check_camera_access("CAM-001", "SE170123", skip_cache=True)
            assert res[0] is False
            assert res.starts_at == now + 3.0

            # Kiểm tra cache RAM
            cached_entry = matcher.access_cache["CAM-001:SE170123"]
            cache_expire = cached_entry[3]
            # TTL của cache phải bị chặn tối đa là starts_at (now + 3.0), không được là now + 5.0
            assert cache_expire == now + 3.0

def test_stale_if_error_skips_denial_when_starts_at_reached():
    from unittest.mock import MagicMock, patch
    from src.core.face_matcher import FaceMatcher, CacheEntry

    matcher = FaceMatcher()
    t0 = 2000.0
    # User bị từ chối lúc t0 với starts_at = 2010.0
    matcher.access_cache["CAM-001:SE170123"] = CacheEntry(
        False, "Chưa tới khung giờ", "Khu vực Lab", t0 + 5.0, "NONE", None,
        expires_at=None, starts_at=t0 + 10.0
    )

    # Khi t = 2011.0 (đã quá starts_at) và backend lỗi/timeout:
    # Không được trả về stale denial "Chưa tới khung giờ"
    with patch("time.time", return_value=t0 + 11.0):
        with patch("requests.post", side_effect=Exception("Connection refused")):
            matcher.is_camera_public = MagicMock(return_value=False)
            res = matcher.check_camera_access("CAM-001", "SE170123")
            assert res[0] is False
            # Lý do không được là "Chưa tới khung giờ", mà là lỗi backend Fail-Closed
            assert "Chưa tới khung giờ" not in res[1]
            assert "Không thể xác thực quyền truy cập" in res[1]

def _bare_pipeline():
    from unittest.mock import MagicMock
    from src.pipeline.video_pipeline import VideoPipeline

    pipeline = VideoPipeline.__new__(VideoPipeline)
    pipeline.camera_code = "CAM-TEST"
    pipeline.analysis_engine = MagicMock()
    pipeline.analysis_engine.active_tracks = {}
    pipeline._record_access_checked = MagicMock()
    return pipeline


def test_apply_access_decision_rearms_past_window_after_clock_skew():
    from unittest.mock import patch
    from src.pipeline.video_pipeline import WINDOW_RECHECK_RETRY_SECONDS

    pipeline = _bare_pipeline()
    face = FaceDetectionResult(
        bbox=BBOX,
        score=0.9,
        matched_code="SE170123",
        matched_name="Minh",
        is_authorized=False
    )
    person = TrackedPerson(track_id=10, bbox=BBOX)
    person.face_info = face
    pipeline.analysis_engine.active_tracks[10] = person

    now = 3000.0
    with patch("time.time", return_value=now):
        # 1. starts_at trong tương lai (now + 5s) -> được ghi nhận
        pipeline._apply_access_decision(face, False, "Chưa tới giờ", "Lab", track_id=10, starts_at=now + 5.0)
        assert face.starts_at == now + 5.0
        assert person.starts_at == now + 5.0

        # 2. Backend vẫn báo "chưa tới giờ" nhưng mốc đã qua theo đồng hồ AI (lệch giờ) -> hẹn hỏi lại sau ít giây
        pipeline._apply_access_decision(face, False, "Chưa tới giờ", "Lab", track_id=10, starts_at=now - 1.0)
        assert face.starts_at == now + WINDOW_RECHECK_RETRY_SECONDS
        assert person.starts_at == now + WINDOW_RECHECK_RETRY_SECONDS

        # 3. Được phép thì bỏ starts_at, chỉ giữ expires_at
        pipeline._apply_access_decision(face, True, "OK", "Lab", track_id=10, starts_at=now + 5.0, expires_at=now + 60.0)
        assert face.starts_at is None
        assert face.expires_at == now + 60.0
        assert person.expires_at == now + 60.0


def test_async_record_roi_access_sets_result_flag_only_for_forced_recheck():
    from unittest.mock import MagicMock
    from src.core.face_matcher import AccessCheckResult

    pipeline = _bare_pipeline()
    face = FaceDetectionResult(bbox=BBOX, score=0.9, matched_code="SE170123", matched_name="Minh", is_authorized=True)
    person = TrackedPerson(track_id=11, bbox=BBOX)
    person.face_info = face
    pipeline.analysis_engine.active_tracks[11] = person
    pipeline.face_matcher = MagicMock()
    pipeline.face_matcher.check_camera_access.return_value = AccessCheckResult(
        False, "Đơn xin quyền đã hết hạn sử dụng trong ngày", "Lab", "NONE", None
    )

    # Sự kiện ROI thường: chỉ ghi log ở backend, không áp kết quả vào track
    pipeline._async_record_roi_access("SE170123", "Cửa A", "ENTRY", 0.0, None, False)
    assert face.is_authorized is True
    assert person.window_recheck_result_ready is False

    # Lượt hỏi lại theo mốc: áp kết quả vào đúng track và đặt cờ
    pipeline._async_record_roi_access("SE170123", "Cửa A", "ENTRY", 0.0, 11, True)
    assert face.is_authorized is False
    assert person.window_recheck_result_ready is True
    _, kwargs = pipeline.face_matcher.check_camera_access.call_args
    assert kwargs.get("skip_cache") is True


def test_associate_face_clears_starts_at_on_grant():
    engine = FrameAnalysisEngine()
    p = TrackedPerson(track_id=5, bbox=BBOX)
    p.starts_at = 1050.0
    p.face_detected = True
    p.face_info = FaceDetectionResult(
        bbox=BBOX,
        score=0.9,
        matched_code="SE170123",
        starts_at=1050.0
    )
    engine.active_tracks[5] = p

    # Gán khuôn mặt mới với starts_at = None và expires_at = 1100.0
    new_face = FaceDetectionResult(
        bbox=BBOX,
        score=0.95,
        matched_code="SE170123",
        matched_name="Minh",
        is_authorized=True,
        starts_at=None,
        expires_at=1100.0
    )
    engine.associate_face(5, new_face)

    assert engine.active_tracks[5].starts_at is None
    assert engine.active_tracks[5].expires_at == 1100.0
