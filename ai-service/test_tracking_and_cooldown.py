import time
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult

def test_track_retention_max_inactive_seconds():
    engine = FrameAnalysisEngine(max_inactive_seconds=10.0)
    bbox = BoundingBox(10, 10, 100, 200, confidence=0.9)
    
    # Frame 1: Person appears at t=100.0
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        current_time=100.0
    )
    assert len(tracks) == 1
    assert 1 in engine.active_tracks

    # Frame 2: Person not detected for 8 seconds (t=108.0) -> still retained (< 10s)
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[],
        current_time=108.0
    )
    assert 1 in engine.active_tracks

    # Frame 3: Person not detected for 11 seconds (t=111.0) -> removed (> 10s)
    tracks, alerts, _ = engine.process_frame(
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

    # t=100.0: Track 1 xuất hiện lần đầu (first seen)
    engine.process_frame(
        detected_tracks=[(1, bbox1)],
        camera_code="CAM-001",
        current_time=100.0
    )
    # t=102.0: Sau 2s (> 1.5s grace period) chưa rõ mặt -> Kích hoạt cảnh báo UNKNOWN_PERSON
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox1)],
        camera_code="CAM-001",
        current_time=102.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"
    assert engine.last_unknown_alert_time["CAM-001"] == 102.0

    # t=105.0: Track 2 xuất hiện (ví dụ quay lưng rồi quay lại nhận ID mới)
    # Vì 105.0 - 102.0 = 3s < 30s cooldown -> Bị chặn (Suppressed)
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(2, bbox2)],
        camera_code="CAM-001",
        current_time=105.0
    )
    assert len(alerts) == 0  # Cooldown suppressed!

    # t=135.0: 33s sau (> 30s cooldown), đối tượng lạ mới xuất hiện
    bbox3 = BoundingBox(30, 30, 130, 230, confidence=0.9)
    # t=135.0: Track 3 first seen
    engine.process_frame(
        detected_tracks=[(3, bbox3)],
        camera_code="CAM-001",
        current_time=135.0
    )
    # t=137.0: Sau 2s (> 1.5s buffer) -> Kích hoạt cảnh báo
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(3, bbox3)],
        camera_code="CAM-001",
        current_time=137.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"
    assert engine.last_unknown_alert_time["CAM-001"] == 137.0

def test_unauthorized_face_not_blocked_by_unknown_cooldown():
    engine = FrameAnalysisEngine(
        max_inactive_seconds=10.0,
        unknown_alert_cooldown=30.0
    )
    bbox = BoundingBox(10, 10, 100, 200, confidence=0.9)

    # t=100.0: Track 1 xuất hiện
    engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=100.0
    )
    # t=102.0: Kích hoạt cảnh báo UNKNOWN
    engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=102.0
    )
    assert engine.last_unknown_alert_time["CAM-001"] == 102.0

    # t=105.0: Khuôn mặt đã nhận diện nhưng KHÔNG CÓ QUYỀN (UNAUTHORIZED_ACCESS)
    # Mặc dù cooldown UNKNOWN đang hoạt động, cảnh báo UNAUTHORIZED VẪN PHẢI BẮN!
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="USER-001",
        matched_name="Minh Nguyen",
        is_authorized=False,
        access_reason="Level 1 khong co quyen vao Area 2"
    )
    engine.associate_face(2, face_info)
    tracks, alerts, _ = engine.process_frame(
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
