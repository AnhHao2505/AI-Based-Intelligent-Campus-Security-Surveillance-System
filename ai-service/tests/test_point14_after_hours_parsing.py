from datetime import datetime, timezone, timedelta
from unittest.mock import patch
import pytest

from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point


def test_after_hours_parses_hh_mm():
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    # 23:00 UTC+7 is after-hours
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_23h,
    )
    # Track 1 is unknown without face -> at night should trigger PERSON_INTRUSION
    # (after grace period 1.5s)
    t_23h_after_grace = t_23h + 2.0
    _, alerts2, _ = engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_23h_after_grace,
    )
    assert len(alerts2) == 1
    assert alerts2[0].event_type == "PERSON_INTRUSION"


def test_after_hours_parses_hh_mm_ss_format():
    # HH:mm:ss with seconds must be parsed correctly instead of failing
    engine = FrameAnalysisEngine(after_hour_start="22:00:00", after_hour_end="06:00:00")
    t_23h = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    t_23h_after_grace = t_23h + 2.0

    engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_23h,
    )
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_23h_after_grace,
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_invalid_format_fallback_to_settings_default():
    # If custom after-hours is corrupt, engine should fall back to settings defaults
    # (settings.DEFAULT_AFTER_HOUR_START="22:00", DEFAULT_AFTER_HOUR_END="06:00")
    engine = FrameAnalysisEngine(after_hour_start="corrupted-time", after_hour_end="06:00")
    # Daytime: 14:00 UTC+7. With fallback 22:00 - 06:00, 14:00 is daytime (after_hours=False)
    t_14h = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    t_14h_after_grace = t_14h + 2.0

    engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_14h,
    )
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
        camera_code="CAM-001",
        current_time=t_14h_after_grace,
    )
    # Since it's daytime via fallback, alert should NOT be PERSON_INTRUSION (it's UNKNOWN_PERSON)
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"


def test_after_hours_fails_safe_when_default_also_invalid():
    # If both custom and settings defaults are invalid, engine must fail-safe (after_hours=True)
    with patch("src.core.frame_analysis_engine.settings.DEFAULT_AFTER_HOUR_START", "bad-default"):
        engine = FrameAnalysisEngine(after_hour_start="corrupted-time", after_hour_end="06:00")
        t_14h = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
        t_14h_after_grace = t_14h + 2.0

        engine.process_frame(
            detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
            camera_code="CAM-001",
            current_time=t_14h,
        )
        _, alerts, _ = engine.process_frame(
            detected_tracks=[(1, BoundingBox(10, 10, 50, 100))],
            camera_code="CAM-001",
            current_time=t_14h_after_grace,
        )
        assert len(alerts) == 1
        assert alerts[0].event_type == "PERSON_INTRUSION"


def test_after_hours_warning_rate_limiting():
    # Warning log must be emitted at most once per 60 seconds per error source
    engine = FrameAnalysisEngine(after_hour_start="corrupted-time", after_hour_end="06:00")
    t0 = 1000.0

    with patch("src.core.frame_analysis_engine.logger.warning") as mock_warn:
        # Frame 1 at t0
        engine.process_frame(detected_tracks=[], camera_code="CAM-001", current_time=t0)
        assert mock_warn.call_count == 1

        # Frame 2 at t0 + 10s (within 60s) -> should NOT log again
        engine.process_frame(detected_tracks=[], camera_code="CAM-001", current_time=t0 + 10.0)
        assert mock_warn.call_count == 1

        # Frame 3 at t0 + 61s (> 60s) -> should log second warning
        engine.process_frame(detected_tracks=[], camera_code="CAM-001", current_time=t0 + 61.0)
        assert mock_warn.call_count == 2

