"""
Một lượt vào ROI chỉ có một cảnh báo (hold buffer + chốt cảnh báo người lạ).

- Hết grace mà KHÔNG thấy mặt nào dùng được -> UNKNOWN ngay (như cũ).
- Hết grace mà ĐANG thấy mặt (chưa khớp) -> giữ thêm tối đa identity_hold_seconds; khớp được trong lúc giữ thì chỉ
  phát đúng một cảnh báo theo danh tính, không có UNKNOWN.
- Đã chốt UNKNOWN rồi mới nhận ra mặt -> không phát thêm UNAUTHORIZED trong unknown_alert_cooldown.
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


def test_late_identity_after_unknown_verdict_does_not_fire_second_alert():
    engine = _engine()
    _step(engine, 0.0)
    assert _step(engine, 1.6)[0] == ["UNKNOWN_PERSON"]

    engine.associate_face(1, _face(2.6, code="U002", authorized=False, checked=True))
    collected = []
    for t in (2.6, 3.0, 10.0):
        collected += _step(engine, t)[0]
    assert collected == []
    assert engine.active_tracks[1].security_display_state == "UNAUTHORIZED"


def test_unknown_verdict_lock_expires_with_unknown_cooldown():
    engine = _engine(unknown_alert_cooldown=5.0, unauthorized_alert_cooldown=5.0)
    _step(engine, 0.0)
    assert _step(engine, 1.6)[0] == ["UNKNOWN_PERSON"]
    engine.associate_face(1, _face(2.6, code="U003", authorized=False, checked=True))
    assert _step(engine, 2.6)[0] == []
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
        collected += _step(engine, t, bbox=outside, roi_polygon=roi)[0]
    assert collected == []


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
