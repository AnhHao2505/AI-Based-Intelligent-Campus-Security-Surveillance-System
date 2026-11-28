import asyncio
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
import requests

from src.config import settings
from src.core.entity import BoundingBox, FaceDetectionResult, MotionResult, Point, RoiPolygonConfig, SecurityAlertEvent
from src.core.face_matcher import FaceMatcher
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.zone_state_machine import ZoneStateMachine
from src.integration.alert_outbox import AlertOutbox
from src.integration.mqtt_client import SecurityMqttClient
from src.pipeline.fmp4_writer import Fmp4VideoWriter
from src.pipeline.video_pipeline import VideoPipeline


# ---------------------------------------------------------------------------
# Issue #1: Alert storm on CAM-005 (after-hours cooldown + evidence video gate)
# ---------------------------------------------------------------------------

def test_after_hours_same_person_in_same_zone_suppressed_across_track_resets():
    """
    1 person standing in the same zone during after-hours whose ByteTrack ID drops/resets
    every 8s (> max_inactive_seconds=4.0s) must still produce only 1 PERSON_INTRUSION incident
    within the configured cooldown window (30s).
    """
    engine = FrameAnalysisEngine(
        after_hour_start="22:00",
        after_hour_end="06:00",
        unknown_alert_cooldown=30.0,
        camera_unknown_min_interval=3.0,
        max_inactive_seconds=4.0,
    )
    base_t = 1700000000.0  # 05:13:20 VN (after-hours)
    roi = [RoiPolygonConfig(label="Hành lang", vertices=[Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)])]
    bbox = BoundingBox(100, 100, 300, 450, 0.9)

    alerts_window1 = []
    # 0..25s (< 30s cooldown), with a 5s gap at sec 5..9 causing track deletion & new track_id=2
    for sec in range(25):
        if 5 <= sec <= 9:
            tracks = []
        elif sec < 5:
            tracks = [(1, bbox)]
        else:
            tracks = [(2, bbox)]
        _, alerts, _ = engine.process_frame(
            detected_tracks=tracks,
            roi_polygons=roi,
            camera_code="CAM-005",
            current_time=base_t + sec,
            frame_size=(640, 480),
        )
        alerts_window1.extend(alerts)

    assert len(alerts_window1) == 1, f"Expected 1 incident within 30s cooldown even after track reset, got {len(alerts_window1)}"
    assert alerts_window1[0].event_type == "PERSON_INTRUSION"


def test_vanished_unverified_in_roi_respects_unknown_cooldown():
    """
    _vanished_unverified_in_roi (_emit_identityless_alert) must NOT emit a second alert
    for the same person/zone within unknown_alert_cooldown.
    """
    engine = FrameAnalysisEngine(
        after_hour_start="22:00",
        after_hour_end="06:00",
        unknown_alert_cooldown=30.0,
        camera_unknown_min_interval=3.0,
        max_inactive_seconds=4.0,
    )
    base_t = 1700000000.0
    roi = [RoiPolygonConfig(label="Hành lang", vertices=[Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)])]
    bbox = BoundingBox(100, 100, 300, 450, 0.9)

    # Track 1 enters, alerts at t+2
    engine.process_frame([(1, bbox)], roi_polygons=roi, camera_code="CAM-005", current_time=base_t, frame_size=(640, 480))
    _, a1, _ = engine.process_frame([(1, bbox)], roi_polygons=roi, camera_code="CAM-005", current_time=base_t + 2.0, frame_size=(640, 480))
    assert len(a1) == 1

    # Track 1 vanishes for 5s (> max_inactive_seconds), then Track 2 enters at same spot for 0.5s and vanishes for 2s (> TRACK_COASTING_SECONDS)
    engine.process_frame([], roi_polygons=roi, camera_code="CAM-005", current_time=base_t + 7.0, frame_size=(640, 480))
    engine.process_frame([(2, bbox)], roi_polygons=roi, camera_code="CAM-005", current_time=base_t + 8.0, frame_size=(640, 480))
    engine.process_frame([(2, bbox)], roi_polygons=roi, camera_code="CAM-005", current_time=base_t + 8.5, frame_size=(640, 480))
    _, a2, _ = engine.process_frame([], roi_polygons=roi, camera_code="CAM-005", current_time=base_t + 10.5, frame_size=(640, 480))
    assert len(a2) == 0, f"Vanished unverified track at same zone within cooldown must be suppressed, got {a2}"


def test_evidence_video_only_starts_for_emitted_incident_not_suppressed():
    """
    VideoPipeline must NOT start a new IncidentRecorder when no incident alert was emitted
    (e.g., alert suppressed by cooldown), even if a violating track remains in active_tracks.
    """
    with patch("src.pipeline.video_pipeline.HumanDetector"), \
         patch("src.pipeline.video_pipeline.FaceDetector"), \
         patch("src.pipeline.video_pipeline.FaceMatcher"), \
         patch("src.pipeline.video_pipeline.MotionDetector"), \
         patch("src.pipeline.video_pipeline.get_security_mqtt_client"), \
         patch("src.pipeline.video_pipeline.StorageService"), \
         patch("src.pipeline.video_pipeline.FrameVisualizer"):
        pipeline = VideoPipeline("CAM-005")
        pipeline.incident_start_delay_seconds = 0.0
        pipeline.incident_no_detection_timeout_seconds = 0.0
        pipeline.enable_motion_gating = False
        pipeline.analysis_engine.after_hour_start = "22:00"
        pipeline.analysis_engine.after_hour_end = "06:00"
        pipeline.analysis_engine.unknown_alert_cooldown = 60.0
        pipeline.face_detector.detect_best_face_in_crop.return_value = None
        pipeline.face_matcher.is_camera_public.return_value = False
        pipeline.face_matcher.get_camera_area_name.return_value = "Hành lang"

        bbox = BoundingBox(100, 100, 300, 450, 0.9)
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        base_t = 1700000000.0
        pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

        with patch("src.pipeline.video_pipeline.incident_recording_manager") as mock_irm:
            rec_state = {"active": False}
            starts = []
            mock_irm.is_recording.side_effect = lambda c: rec_state["active"]
            mock_irm.start_incident_recording.side_effect = lambda **kw: (rec_state.update(active=True), starts.append(kw))
            mock_irm.stop_camera_recordings.side_effect = lambda c: rec_state.update(active=False)

            # Frame 0..2: person enters, emits 1 alert at t+2 -> starts 1 recording
            for s in (0, 1, 2):
                pipeline.process_frame(frame, current_time=base_t + s)
            assert len(starts) == 1

            # Simulate external stop (e.g. guard clicked "Đã xem" or recording ended) while person is still in frame
            # and alert is suppressed by 60s cooldown -> must NOT start a second recording without a new emitted alert
            rec_state["active"] = False
            pipeline.reset_incident_state()
            for s in (5, 6, 7, 8):
                _, _, alerts = pipeline.process_frame(frame, current_time=base_t + s)
                assert alerts == []
            assert len(starts) == 1, f"Suppressed frames must not trigger new evidence video, got starts={len(starts)}"


# ---------------------------------------------------------------------------
# Issue #2: checkEntry timeout marked distinctly (after-hours & daytime)
# ---------------------------------------------------------------------------

def test_check_entry_timeout_after_hours_marked_distinctly():
    """
    When check-entry times out and fails closed at night, the emitted alert must NOT hide
    the timeout behind 'Lý do: Ngoài giờ hoạt động'; it must carry verification_status='UNVERIFIED_TIMEOUT'
    and clearly state Backend không phản hồi in details.
    """
    with patch("src.core.face_matcher.FaceMatcher._get_connection"):
        matcher = FaceMatcher(db_host="localhost", db_port=5432, db_user="u", db_pass="p", db_name="d")
    matcher.public_camera_codes = set()
    matcher.camera_area_map = {"CAM-005": "Phòng Server"}

    with patch("requests.post", side_effect=requests.exceptions.ReadTimeout("timeout 1.5s")):
        res = matcher.check_camera_access("CAM-005", "EMP001", skip_cache=True)

    assert res[0] is False
    assert getattr(res, "verification_status", None) == "UNVERIFIED_TIMEOUT"

    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_night = datetime(2026, 11, 4, 23, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="EMP001",
        matched_name="Nguyen Van A",
        is_authorized=res[0],
        access_checked=True,
        access_reason=res[1],
        area_name=res[2],
        access_verification_status=getattr(res, "verification_status", None),
    )
    engine.associate_face(track_id=1, face_result=face_info)

    _, alerts, _ = engine.process_frame(detected_tracks=[(1, bbox)], camera_code="CAM-005", current_time=t_night)
    assert len(alerts) == 1
    alert = alerts[0]
    assert alert.event_type == "PERSON_INTRUSION"
    assert alert.verification_status == "UNVERIFIED_TIMEOUT"
    assert "Backend không phản hồi" in alert.details
    assert alert.to_dict().get("verification_status") == "UNVERIFIED_TIMEOUT"


# ---------------------------------------------------------------------------
# Issue #3 (Low): macOS libavdevice duplication isolation
# ---------------------------------------------------------------------------

def test_fmp4_writer_does_not_import_av_at_module_import_time():
    """
    Importing fmp4_writer (or main) must NOT eagerly import `av` in the cv2 process at startup.
    And on darwin, Fmp4VideoWriter must isolate PyAV encoding in a child process without importing `av` in-process.
    """
    code = (
        "import sys; "
        "import src.pipeline.fmp4_writer; "
        "assert 'av' not in sys.modules, 'av must not be imported eagerly at module import time'"
    )
    env = dict(os.environ)
    env.setdefault("POSTGRES_PASSWORD", "test_only_password_123")
    env.setdefault("MINIO_ROOT_USER", "testuser")
    env.setdefault("MINIO_ROOT_PASSWORD", "testpassword123")
    env.setdefault("INTERNAL_SERVICE_KEY", "x" * 32)
    proc = subprocess.run(
        [sys.executable, "-c", code],
        cwd=os.path.abspath(os.path.join(os.path.dirname(__file__), "..")),
        env=env,
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0, f"stderr={proc.stderr}"


def test_fmp4_writer_isolated_subprocess_writes_valid_mp4(tmp_path):
    """
    When running in isolated subprocess mode (used on macOS darwin to avoid cv2 vs av libavdevice collision),
    Fmp4VideoWriter writes a valid fragmented MP4 file with moov/moof atoms without importing `av` in-process.
    """
    out_file = tmp_path / "isolated_test.mp4"
    with patch("src.pipeline.fmp4_writer._should_isolate_av_subprocess", return_value=True):
        writer = Fmp4VideoWriter(str(out_file), fps=10.0, frame_size=(160, 120), keyframe_interval=5)
        assert writer.isOpened() is True
        frame = np.zeros((120, 160, 3), dtype=np.uint8)
        for _ in range(10):
            writer.write(frame)
        writer.release()

    assert out_file.exists()
    data = out_file.read_bytes()
    assert len(data) > 0
    assert b"moov" in data, "Isolated fMP4 writer must produce valid moov atom"


# ---------------------------------------------------------------------------
# Issue #4 (Low): main.py must not hardcode "CAM-001" for default_pipeline
# ---------------------------------------------------------------------------

def test_resolve_default_camera_code_uses_configured_or_db_camera_not_cam001():
    """
    When DB/cache only contains CAM-005 (and no CAM-001), resolve_default_camera_code()
    must return CAM-005 instead of hardcoded CAM-001. When none exist, return neutral CAM-DEFAULT.
    """
    import src.main as main_mod

    assert hasattr(main_mod, "resolve_default_camera_code"), "main.py must expose resolve_default_camera_code()"
    fake_cache = MagicMock()
    fake_cache.camera_area_map = {"CAM-005": "Hành lang tầng 2"}
    with patch.object(settings, "DEFAULT_CAMERA_CODE", None, create=True), \
         patch("src.core.shared_face_cache.get_shared_face_cache", return_value=fake_cache):
        assert main_mod.resolve_default_camera_code() == "CAM-005"

    empty_cache = MagicMock()
    empty_cache.camera_area_map = {}
    empty_cache.refresh_cache.return_value = False
    with patch.object(settings, "DEFAULT_CAMERA_CODE", None, create=True), \
         patch("src.core.shared_face_cache.get_shared_face_cache", return_value=empty_cache), \
         patch.object(main_mod, "_query_first_camera_code_from_db", return_value=None):
        assert main_mod.resolve_default_camera_code() != "CAM-001"


# ---------------------------------------------------------------------------
# Issue #3 (NFR 3s timestamps): frame_captured_at & mqtt_published_at
# ---------------------------------------------------------------------------

def test_nfr_timestamps_propagated_on_alert_and_mqtt_publish(tmp_path, monkeypatch):
    """
    VideoPipeline.process_frame propagates frame_captured_at onto SecurityAlertEvent,
    and SecurityMqttClient stamps mqtt_published_at right before publishing.
    """
    with patch("src.pipeline.video_pipeline.HumanDetector"), \
         patch("src.pipeline.video_pipeline.FaceDetector"), \
         patch("src.pipeline.video_pipeline.FaceMatcher"), \
         patch("src.pipeline.video_pipeline.MotionDetector"), \
         patch("src.pipeline.video_pipeline.get_security_mqtt_client"), \
         patch("src.pipeline.video_pipeline.StorageService"), \
         patch("src.pipeline.video_pipeline.FrameVisualizer"):
        pipeline = VideoPipeline("CAM-005")
        pipeline.enable_motion_gating = False
        pipeline.face_detector.detect_best_face_in_crop.return_value = None
        pipeline.face_matcher.is_camera_public.return_value = False
        pipeline.face_matcher.get_camera_area_name.return_value = "Hành lang"

        bbox = BoundingBox(100, 100, 300, 450, 0.9)
        frame = np.zeros((480, 640, 3), dtype=np.uint8)
        pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

        capture_epoch = 1700000000.123
        with patch("src.pipeline.video_pipeline.incident_recording_manager"):
            pipeline.process_frame(frame, current_time=capture_epoch, frame_captured_at=capture_epoch)
            _, _, alerts = pipeline.process_frame(frame, current_time=capture_epoch + 2.0, frame_captured_at=capture_epoch + 2.0)

        assert len(alerts) == 1
        d = alerts[0].to_dict()
        assert "frame_captured_at" in d and d["frame_captured_at"].endswith("Z")

    from src.integration import mqtt_client as mqtt_mod
    fake_paho = MagicMock()
    fake_info = MagicMock(mid=1, rc=0)
    fake_paho.Client.return_value.publish.return_value = fake_info
    fake_paho.CallbackAPIVersion.VERSION2 = "VERSION2"
    fake_paho.MQTT_ERR_SUCCESS = 0
    monkeypatch.setattr(mqtt_mod, "mqtt", fake_paho)
    monkeypatch.setattr(settings, "MQTT_ENABLED", True)
    monkeypatch.setattr(settings, "MQTT_PASSWORD", "")

    box = AlertOutbox(str(tmp_path / "outbox.sqlite3"))
    try:
        client = SecurityMqttClient(host="localhost", port=1883, outbox=box, start_flusher=False)
        client._on_connect(client.client, None, {}, 0)
        evt = SecurityAlertEvent(camera_code="CAM-005", event_type="PERSON_INTRUSION", frame_captured_at="2026-11-26T16:00:00.100Z")
        client.send_alert(evt)
        pub_kwargs = client.client.publish.call_args.kwargs
        sent_payload = json.loads(pub_kwargs["payload"])
        assert "mqtt_published_at" in sent_payload and sent_payload["mqtt_published_at"].endswith("Z")
        assert sent_payload["frame_captured_at"] == "2026-11-26T16:00:00.100Z"
        client.close()
    finally:
        box.close()
