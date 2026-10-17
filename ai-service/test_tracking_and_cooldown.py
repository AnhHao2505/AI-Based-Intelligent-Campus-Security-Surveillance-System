import time
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult

def test_track_retention_max_inactive_seconds():
    engine = FrameAnalysisEngine(max_inactive_seconds=10.0)
    bbox = BoundingBox(10, 10, 100, 200, confidence=0.9)
    
    # Frame 1: Person appears at t=100.0
    tracks, alerts = engine.process_frame(
        detected_tracks=[(1, bbox)],
        current_time=100.0
    )
    assert len(tracks) == 1
    assert 1 in engine.active_tracks

    # Frame 2: Person not detected for 8 seconds (t=108.0) -> still retained (< 10s)
    tracks, alerts = engine.process_frame(
        detected_tracks=[],
        current_time=108.0
    )
    assert 1 in engine.active_tracks

    # Frame 3: Person not detected for 11 seconds (t=111.0) -> removed (> 10s)
    tracks, alerts = engine.process_frame(
        detected_tracks=[],
        current_time=111.0
    )
    assert 1 not in engine.active_tracks

def test_unknown_person_camera_cooldown():
    engine = FrameAnalysisEngine(
        max_inactive_seconds=10.0,
        unknown_alert_cooldown=30.0
    )
    bbox1 = BoundingBox(10, 10, 100, 200, confidence=0.9)
    bbox2 = BoundingBox(20, 20, 120, 220, confidence=0.9)

    # t=100.0: Track 1 appears with no face -> triggers UNKNOWN_PERSON alert
    tracks, alerts = engine.process_frame(
        detected_tracks=[(1, bbox1)],
        camera_code="CAM-001",
        current_time=100.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"
    assert engine.last_unknown_alert_time["CAM-001"] == 100.0

    # t=105.0: Track 2 appears (e.g. user turned back and ByteTrack created a new ID)
    # Since 105.0 - 100.0 = 5s < 30s cooldown, alert MUST BE SUPPRESSED
    tracks, alerts = engine.process_frame(
        detected_tracks=[(2, bbox2)],
        camera_code="CAM-001",
        current_time=105.0
    )
    assert len(alerts) == 0  # Cooldown suppressed!

    # t=131.0: 31s later (> 30s cooldown), a new unknown detection occurs
    bbox3 = BoundingBox(30, 30, 130, 230, confidence=0.9)
    tracks, alerts = engine.process_frame(
        detected_tracks=[(3, bbox3)],
        camera_code="CAM-001",
        current_time=131.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"
    assert engine.last_unknown_alert_time["CAM-001"] == 131.0

def test_unauthorized_face_not_blocked_by_unknown_cooldown():
    engine = FrameAnalysisEngine(
        max_inactive_seconds=10.0,
        unknown_alert_cooldown=30.0
    )
    bbox = BoundingBox(10, 10, 100, 200, confidence=0.9)

    # t=100.0: Unknown alert triggered on CAM-001
    engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=100.0
    )
    assert engine.last_unknown_alert_time["CAM-001"] == 100.0

    # t=105.0: A known face with UNAUTHORIZED_ACCESS appears on CAM-001
    # Even though cooldown is active for UNKNOWN, UNAUTHORIZED MUST STILL TRIGGER!
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="USER-001",
        matched_name="Minh Nguyen",
        is_authorized=False,
        access_reason="Level 1 khong co quyen vao Area 2"
    )
    engine.associate_face(2, face_info)
    tracks, alerts = engine.process_frame(
        detected_tracks=[(2, bbox)],
        camera_code="CAM-001",
        current_time=105.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNAUTHORIZED_ACCESS"

if __name__ == "__main__":
    test_track_retention_max_inactive_seconds()
    test_unknown_person_camera_cooldown()
    test_unauthorized_face_not_blocked_by_unknown_cooldown()
    print("ALL TESTS PASSED!")
