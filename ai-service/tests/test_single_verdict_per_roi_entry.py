"""
Một lượt vào ROI chỉ có một cảnh báo (hold buffer + chốt cảnh báo người lạ).

- Hết grace mà KHÔNG thấy mặt nào dùng được -> UNKNOWN ngay (như cũ).
- Hết grace mà ĐANG thấy mặt (chưa khớp) -> giữ thêm tối đa identity_hold_seconds; khớp được trong lúc giữ thì chỉ
  phát đúng một cảnh báo theo danh tính, không có UNKNOWN.
- Đã chốt UNKNOWN rồi mới nhận ra mặt -> không phát thêm UNAUTHORIZED trong unknown_alert_cooldown, chỉ gửi
  IDENTITY_UPDATE (1 lần, cùng roi_session_id với cảnh báo cũ) để Backend cập nhật / tự đóng sự cố.
- Rời ROI trong lúc giữ -> Early Exit phát UNKNOWN ngay (fail-secure).
"""
from src.config import settings
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.pipeline.runtime_config import apply_runtime_config

# current_time = 0 tương ứng 07:00 giờ Việt Nam -> trong giờ hoạt động (ngoài giờ mặc định 22:00–06:00)
CAM = "CAM-HOLD"
BBOX = BoundingBox(50, 50, 100, 150)


def _engine(**kw):
    engine = FrameAnalysisEngine(
        access_grace_period=1.5,
        unknown_alert_cooldown=kw.pop("unknown_alert_cooldown", 30.0),
        unauthorized_alert_cooldown=kw.pop("unauthorized_alert_cooldown", 30.0),
        camera_unknown_min_interval=0.0,
        **kw,
    )
    engine.identity_hold_seconds = 1.0
    return engine


def _face(t, code=None, authorized=False, checked=False):
    return FaceDetectionResult(
        bbox=BoundingBox(60, 55, 80, 75),
        score=0.6 if code else 0.2,
        matched_code=code,
        matched_name=f"Người {code}" if code else None,
        is_authorized=authorized,
        access_checked=checked,
        matched_at=t if code else None,
        seen_at=t,
    )


def _step(engine, t, bbox=BBOX, **kw):
    _, alerts, events = engine.process_frame(detected_tracks=[(1, bbox)], camera_code=CAM, current_time=t, **kw)
    return [a.event_type for a in alerts], events


def _step_raw(engine, t, bbox=BBOX, **kw):
    _, alerts, events = engine.process_frame(detected_tracks=[(1, bbox)], camera_code=CAM, current_time=t, **kw)
    return alerts, events


def test_no_usable_face_fires_unknown_right_after_grace():
    engine = _engine()
    assert _step(engine, 0.0)[0] == []
    assert _step(engine, 1.0)[0] == []
    assert _step(engine, 1.6)[0] == ["UNKNOWN_PERSON"]


def test_usable_face_holds_then_fires_unknown_at_hard_cap():
    engine = _engine()
    _step(engine, 0.0)
    engine.associate_face(1, _face(0.5))
    _step(engine, 0.5)

    alerts, events = _step(engine, 1.6)
    assert alerts == []
    assert engine.active_tracks[1].security_display_state == "VERIFYING"
    assert not [e for e in events if e["direction"] == "ENTRY"], "Đang giữ: chưa ghi ENTRY UNKNOWN"
    assert _step(engine, 2.4)[0] == []

    alerts, events = _step(engine, 2.6)
    assert alerts == ["UNKNOWN_PERSON"]
    assert [e["user_code"] for e in events if e["direction"] == "ENTRY"] == ["UNKNOWN"]


def test_match_during_hold_gives_single_unauthorized_alert():
    engine = _engine()
    _step(engine, 0.0)
    engine.associate_face(1, _face(0.5))
    _step(engine, 0.5)
    assert _step(engine, 1.6)[0] == []

    engine.associate_face(1, _face(2.0, code="U001", authorized=False, checked=True))
    alerts, events = _step(engine, 2.0)
    assert alerts == ["UNAUTHORIZED_PERSON"]
    assert [e["user_code"] for e in events if e["direction"] == "ENTRY"] == ["U001"]

    collected = []
    for t in (2.6, 3.0, 5.0):
        collected += _step(engine, t)[0]
    assert collected == []


def test_match_during_hold_authorized_gives_no_alert():
    engine = _engine()
    _step(engine, 0.0)
    engine.associate_face(1, _face(0.5))
    _step(engine, 0.5)
    _step(engine, 1.6)

    engine.associate_face(1, _face(2.0, code="A001", authorized=True, checked=True))
    collected = []
    for t in (2.0, 2.6, 4.0):
        collected += _step(engine, t)[0]
    assert collected == []
    assert engine.active_tracks[1].security_display_state == "AUTHORIZED"


def test_late_identity_after_unknown_verdict_sends_identity_update_not_second_alert():
    engine = _engine()
    _step(engine, 0.0)
    unknown, _ = _step_raw(engine, 1.6)
    assert [a.event_type for a in unknown] == ["UNKNOWN_PERSON"]
    session_id = unknown[0].roi_session_id
    assert session_id and unknown[0].to_dict()["roi_session_id"] == session_id

    engine.associate_face(1, _face(2.6, code="U002", authorized=False, checked=True))
    collected = []
    for t in (2.6, 3.0, 10.0):
        collected += _step_raw(engine, t)[0]
    assert [a.event_type for a in collected] == ["IDENTITY_UPDATE"], "Không có cảnh báo thứ hai, chỉ 1 bản cập nhật"
    update = collected[0].to_dict()
    assert update["roi_session_id"] == session_id
    assert update["identity_outcome"] == "UNAUTHORIZED"
    assert update["user_code"] == "U002" and update["user_name"] == "Người U002"
    assert "Người U002 (U002)" in update["details"]
    assert engine.active_tracks[1].security_display_state == "UNAUTHORIZED"


def test_late_authorized_identity_sends_authorized_update_once():
    engine = _engine()
    _step(engine, 0.0)
    unknown, _ = _step_raw(engine, 1.6)
    engine.associate_face(1, _face(2.6, code="A002", authorized=True, checked=True))
    collected = []
    for t in (2.6, 3.0, 4.0):
        collected += _step_raw(engine, t)[0]
    assert [(a.event_type, a.identity_outcome) for a in collected] == [("IDENTITY_UPDATE", "AUTHORIZED")]
    assert collected[0].roi_session_id == unknown[0].roi_session_id
    assert engine.active_tracks[1].security_display_state == "AUTHORIZED"


def test_identified_on_time_sends_no_identity_update():
    engine = _engine()
    _step(engine, 0.0)
    engine.associate_face(1, _face(0.5, code="A003", authorized=True, checked=True))
    collected = []
    for t in (0.5, 1.6, 3.0):
        collected += _step(engine, t)[0]
    assert collected == []


def test_late_identity_logs_extra_entry_with_real_code():
    engine = _engine()
    _step(engine, 0.0)
    _, events = _step(engine, 1.6)
    assert [e["user_code"] for e in events if e["direction"] == "ENTRY"] == ["UNKNOWN"]
    engine.associate_face(1, _face(2.6, code="U005", authorized=False, checked=True))
    entries = []
    for t in (2.6, 3.0):
        entries += [e["user_code"] for e in _step(engine, t)[1] if e["direction"] == "ENTRY"]
    assert entries == ["U005"], "Ghi bổ sung đúng 1 ENTRY mang mã người thật"


def test_unknown_verdict_lock_expires_with_unknown_cooldown():
    engine = _engine(unknown_alert_cooldown=5.0, unauthorized_alert_cooldown=5.0)
    _step(engine, 0.0)
    assert _step(engine, 1.6)[0] == ["UNKNOWN_PERSON"]
    engine.associate_face(1, _face(2.6, code="U003", authorized=False, checked=True))
    assert _step(engine, 2.6)[0] == ["IDENTITY_UPDATE"]
    # Hết cooldown tính từ lúc chốt UNKNOWN (1.6 + 5.0): người vẫn còn đó thì được cảnh báo lại như bình thường
    assert _step(engine, 7.0)[0] == ["UNAUTHORIZED_PERSON"]


def test_late_identity_outside_roi_does_not_fire_second_alert():
    engine = _engine()
    roi = [Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200)]
    inside = BBOX
    outside = BoundingBox(250, 250, 300, 350)
    _step(engine, 0.0, bbox=inside, roi_polygon=roi)
    assert _step(engine, 1.6, bbox=inside, roi_polygon=roi)[0] == ["UNKNOWN_PERSON"]
    _step(engine, 2.0, bbox=outside, roi_polygon=roi)

    engine.associate_face(1, _face(2.5, code="U004", authorized=False, checked=True))
    collected = []
    for t in (2.5, 3.0):
        collected += _step_raw(engine, t, bbox=outside, roi_polygon=roi)[0]
    assert [(a.event_type, a.identity_outcome, a.user_code) for a in collected] == [("IDENTITY_UPDATE", "UNAUTHORIZED", "U004")]


def test_leaving_roi_during_hold_fires_unknown_immediately():
    engine = _engine()
    roi = [Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200)]
    outside = BoundingBox(250, 250, 300, 350)
    _step(engine, 0.0, roi_polygon=roi)
    engine.associate_face(1, _face(0.5))
    _step(engine, 0.5, roi_polygon=roi)
    assert _step(engine, 1.8, roi_polygon=roi)[0] == []
    assert _step(engine, 2.0, bbox=outside, roi_polygon=roi)[0] == ["UNKNOWN_PERSON"]


def test_face_seen_before_roi_entry_does_not_extend_hold():
    engine = _engine()
    roi = [Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200)]
    outside = BoundingBox(250, 250, 300, 350)
    _step(engine, 0.0, bbox=outside, roi_polygon=roi)
    engine.associate_face(1, _face(0.5))
    _step(engine, 0.5, bbox=outside, roi_polygon=roi)
    _step(engine, 1.0, roi_polygon=roi)
    assert _step(engine, 2.6, roi_polygon=roi)[0] == ["UNKNOWN_PERSON"]


def test_runtime_config_applies_identity_hold_seconds():
    class _Pipe:
        def __init__(self):
            self.analysis_engine = _engine()

    pipe = _Pipe()
    old = settings.ACCESS_RESOLUTION_HOLD_SECONDS
    try:
        values, errors = apply_runtime_config({"identity_hold_seconds": 2.0}, pipe, [])
        assert errors == []
        assert values == {"identity_hold_seconds": 2.0}
        assert pipe.analysis_engine.identity_hold_seconds == 2.0
        assert settings.ACCESS_RESOLUTION_HOLD_SECONDS == 2.0
    finally:
        settings.ACCESS_RESOLUTION_HOLD_SECONDS = old


def _frames(engine, times, tracks, **kw):
    collected = []
    for t in times:
        _, alerts, _ = engine.process_frame(detected_tracks=tracks, camera_code=CAM, current_time=t, **kw)
        collected += [(a.track_id, a.event_type) for a in alerts]
    return collected


def test_unverified_person_vanishing_inside_roi_fires_unknown_once():
    # Đi thẳng qua cửa nằm trong ROI: điểm chân không ra khỏi ROI, track biến mất khi chưa xác thực
    engine = _engine()
    assert _frames(engine, (0.0, 0.5), [(1, BBOX)]) == []
    assert _frames(engine, (1.0, 1.9), []) == [], "Chưa quá thời gian coasting thì chưa kết luận biến mất"
    assert _frames(engine, (2.1,), []) == [(1, "UNKNOWN_PERSON")]
    assert _frames(engine, (3.0, 6.0, 9.0, 11.0), []) == [], "Chỉ cảnh báo một lần"


def test_vanished_duplicate_box_overlapping_visible_person_is_not_alerted():
    # Hai box lệch nhau của cùng một người (không bị lọc box kép trong frame); box thừa biến mất, người thật vẫn đứng đó
    engine = _engine()
    other = BoundingBox(65, 50, 115, 150)
    _frames(engine, (0.0, 0.5), [(1, BBOX), (2, other)])
    alerts = _frames(engine, (1.0, 1.6, 2.2, 3.0), [(1, BBOX)])
    assert alerts == [(1, "UNKNOWN_PERSON")], "Chỉ người đang thấy bị cảnh báo, box thừa biến mất thì không"


def test_track_reappearing_after_vanish_alert_inherits_verdict():
    engine = _engine()
    _frames(engine, (0.0, 0.5), [(1, BBOX)])
    assert _frames(engine, (2.1,), []) == [(1, "UNKNOWN_PERSON")]
    # ByteTrack cấp ID mới khi người xuất hiện lại gần chỗ cũ: kế thừa cảnh báo đã chốt, không báo lại
    assert _frames(engine, (2.4, 3.0, 4.5), [(7, BBOX)]) == []
    engine.associate_face(7, _face(4.6, code="U009", authorized=False, checked=True))
    assert _frames(engine, (4.6, 5.0), [(7, BBOX)]) == [(7, "IDENTITY_UPDATE")], "Cập nhật sự cố cũ, không cảnh báo mới"


def test_vanish_in_public_area_during_daytime_is_not_alerted():
    engine = _engine()
    _frames(engine, (0.0, 0.5), [(1, BBOX)], is_public_area=True)
    assert _frames(engine, (2.1, 3.0), [], is_public_area=True) == []


def test_runtime_config_applies_camera_unknown_min_interval():
    class _Pipe:
        def __init__(self):
            self.analysis_engine = _engine()

    pipe = _Pipe()
    old = settings.CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS
    try:
        values, errors = apply_runtime_config({"camera_unknown_min_interval": 7}, pipe, [])
        assert errors == []
        assert pipe.analysis_engine.camera_unknown_min_interval == 7.0
        assert settings.CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS == 7.0
    finally:
        settings.CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS = old


def test_bytetrack_id_switch_within_max_inactive_seconds_emits_identity_update_not_second_incident():
    # Tái hiện lỗi thực tế ngoài giờ (22:13): người bước vào ROI 1 lần, chốt PERSON_INTRUSION khi chưa thấy mặt,
    # sau đó khuất / trượt YOLO 3.5s (> 2.5s nhưng <= max_inactive_seconds=4.0s) rồi xuất hiện lại với Track ID mới #32
    # và nhận ra mặt FM-007 -> chỉ được gửi IDENTITY_UPDATE vào sự cố cũ, KHÔNG được phát PERSON_INTRUSION thứ hai.
    from datetime import datetime
    from zoneinfo import ZoneInfo

    t0 = datetime(2026, 11, 26, 22, 13, 35, tzinfo=ZoneInfo("Asia/Ho_Chi_Minh")).timestamp()
    engine = _engine(
        max_inactive_seconds=4.0,
        after_hour_start="22:00",
        after_hour_end="06:00",
        unknown_alert_cooldown=30.0,
        unauthorized_alert_cooldown=60.0,
    )
    roi = [Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200)]

    _, a0, _ = engine.process_frame([(31, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0, area_name="Văn Phòng")
    assert a0 == []

    _, a1, _ = engine.process_frame([(31, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 2.0, area_name="Văn Phòng")
    assert [a.event_type for a in a1] == ["PERSON_INTRUSION"]
    session_id = a1[0].roi_session_id
    assert session_id is not None
    assert "FM-007" not in a1[0].details

    # Track #31 mất dấu 3.5s (> 2.5s, <= max_inactive_seconds=4.0s), ByteTrack cấp ID mới #32 tại t0 + 5.5s
    _, a_gap, _ = engine.process_frame([], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 3.8, area_name="Văn Phòng")
    assert a_gap == []

    _, a_reappear, _ = engine.process_frame([(32, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 5.5, area_name="Văn Phòng")
    assert a_reappear == []

    # Đến t0 + 10.0s (22:13:45, cách sự cố đầu 8s) nhận ra mặt Anh Hào (FM-007) trên Track #32
    face_fm = FaceDetectionResult(
        bbox=BoundingBox(60, 55, 80, 75),
        score=0.78,
        matched_code="FM-007",
        matched_name="Anh Hào",
        role="FACILITY_MANAGER",
        is_authorized=False,
        access_checked=True,
        access_reason="Ngoài giờ hoạt động",
        matched_at=t0 + 10.0,
        seen_at=t0 + 10.0,
    )
    engine.associate_face(32, face_fm)
    _, a2, _ = engine.process_frame([(32, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 10.0, area_name="Văn Phòng")

    assert [a.event_type for a in a2] == ["IDENTITY_UPDATE"], f"Kỳ vọng đúng 1 IDENTITY_UPDATE, thực tế: {[a.event_type for a in a2]}"
    assert a2[0].roi_session_id == session_id
    assert a2[0].identity_outcome == "UNAUTHORIZED"
    assert a2[0].user_code == "FM-007"
    assert "Anh Hào (FM-007)" in a2[0].details


def test_coasting_nms_transfers_unknown_verdict_session_in_both_directions():
    # (b) Khi Coasting NMS gộp track đang coasting sang track đang thấy (cả hai chiều tid nhỏ->lớn và lớn->nhỏ),
    # track mới phải giữ unknown_verdict_time, unknown_verdict_session_id, identity_update_sent, entry_logged_as_unknown.
    shifted = BoundingBox(65, 50, 115, 150)  # IoU ~ 0.54 với BBOX nhưng dx = 15 >= 20% width -> không bị lọc double-box

    for old_tid, new_tid, code in ((1, 5, "U010"), (10, 2, "U011")):
        engine = _engine(max_inactive_seconds=4.0, unknown_alert_cooldown=30.0)
        engine.process_frame([(old_tid, BBOX)], camera_code=CAM, current_time=0.0)
        _, first_alerts, first_events = engine.process_frame([(old_tid, BBOX)], camera_code=CAM, current_time=1.6)
        assert [a.event_type for a in first_alerts] == ["UNKNOWN_PERSON"]
        assert [e["user_code"] for e in first_events if e["direction"] == "ENTRY"] == ["UNKNOWN"]
        session_id = first_alerts[0].roi_session_id

        # Frame chuyển giao: hai box cùng xuất hiện (chưa bị lọc double-box), sau đó old_tid coasting và new_tid đè lên BBOX
        engine.process_frame([(old_tid, BBOX), (new_tid, shifted)], camera_code=CAM, current_time=1.8)
        _, coast_alerts, coast_events = engine.process_frame([(new_tid, BBOX)], camera_code=CAM, current_time=2.0)
        assert coast_alerts == []
        assert not [e for e in coast_events if e["direction"] == "ENTRY"]

        transferred = engine.active_tracks[new_tid]
        assert transferred.unknown_verdict_time == 1.6
        assert transferred.unknown_verdict_session_id == session_id
        assert transferred.identity_update_sent is False
        assert transferred.entry_logged_as_unknown is True

        # Nhận ra mặt trên new_tid -> gửi IDENTITY_UPDATE cho phiên cũ + ghi bổ sung 1 ENTRY mã thật, không phát sự cố thứ hai
        engine.associate_face(new_tid, _face(2.4, code=code, authorized=False, checked=True))
        _, second_alerts, second_events = engine.process_frame([(new_tid, BBOX)], camera_code=CAM, current_time=2.4)
        assert [a.event_type for a in second_alerts] == ["IDENTITY_UPDATE"]
        assert second_alerts[0].roi_session_id == session_id
        assert second_alerts[0].user_code == code
        assert [e["user_code"] for e in second_events if e["direction"] == "ENTRY"] == [code]


def test_negative_stranger_deleted_after_max_inactive_then_other_person_creates_new_incident():
    # (c) Ca âm: người lạ chốt cảnh báo, track bị xoá (> max_inactive_seconds), sau đó một người khác được nhận ra
    # trong cùng cửa sổ unknown_alert_cooldown -> PHẢI tạo sự cố mới, sự cố người lạ ban đầu giữ nguyên không có tên
    # (và nếu người vào sau có quyền hợp lệ thì cũng KHÔNG được phát IDENTITY_UPDATE làm tự đóng sự cố người lạ).
    from datetime import datetime
    from zoneinfo import ZoneInfo

    t0 = datetime(2026, 11, 26, 22, 13, 35, tzinfo=ZoneInfo("Asia/Ho_Chi_Minh")).timestamp()
    engine = _engine(
        max_inactive_seconds=4.0,
        after_hour_start="22:00",
        after_hour_end="06:00",
        unknown_alert_cooldown=30.0,
        unauthorized_alert_cooldown=60.0,
    )
    roi = [Point(0, 0), Point(200, 0), Point(200, 200), Point(0, 200)]

    engine.process_frame([(1, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0, area_name="Văn Phòng")
    _, stranger_alerts, _ = engine.process_frame([(1, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 2.0, area_name="Văn Phòng")
    assert [a.event_type for a in stranger_alerts] == ["PERSON_INTRUSION"]
    stranger_session = stranger_alerts[0].roi_session_id
    assert stranger_session is not None
    assert "Tên:" not in stranger_alerts[0].details

    # Quá max_inactive_seconds (4.0s): Track #1 bị xoá hoàn toàn khỏi active_tracks tại t0 + 6.5s
    engine.process_frame([], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 6.5, area_name="Văn Phòng")
    assert 1 not in engine.active_tracks

    # Trường hợp 1: bảo vệ có quyền hợp lệ (GUARD) bước vào lúc t0 + 7.5s -> KHÔNG được phát IDENTITY_UPDATE đóng nhầm sự cố kẻ lạ
    guard_face = FaceDetectionResult(
        bbox=BoundingBox(60, 55, 80, 75),
        score=0.85,
        matched_code="BV-001",
        matched_name="Bảo Vệ Ca Đêm",
        role="GUARD",
        is_authorized=True,
        access_checked=True,
        matched_at=t0 + 7.5,
        seen_at=t0 + 7.5,
    )
    engine.associate_face(20, guard_face)
    _, guard_alerts, _ = engine.process_frame([(20, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 7.5, area_name="Văn Phòng")
    assert guard_alerts == []

    # Bảo vệ rời đi quá max_inactive_seconds, người khác không có quyền (FM-007) bước vào lúc t0 + 15.0s (< 30s cooldown)
    engine.process_frame([], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 12.0, area_name="Văn Phòng")
    other_face = FaceDetectionResult(
        bbox=BoundingBox(60, 55, 80, 75),
        score=0.80,
        matched_code="FM-007",
        matched_name="Anh Hào",
        role="FACILITY_MANAGER",
        is_authorized=False,
        access_checked=True,
        access_reason="Ngoài giờ hoạt động",
        matched_at=t0 + 15.0,
        seen_at=t0 + 15.0,
    )
    engine.associate_face(32, other_face)
    _, second_alerts, _ = engine.process_frame([(32, BBOX)], roi_polygon=roi, camera_code="CAM-005", current_time=t0 + 15.0, area_name="Văn Phòng")
    assert [a.event_type for a in second_alerts] == ["PERSON_INTRUSION"]
    assert second_alerts[0].roi_session_id is None
    assert second_alerts[0].roi_session_id != stranger_session
    assert "Anh Hào (FM-007)" in second_alerts[0].details
