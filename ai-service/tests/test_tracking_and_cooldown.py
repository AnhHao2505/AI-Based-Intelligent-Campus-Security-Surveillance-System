import time
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult, RoiPolygonConfig

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
    _, first_alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=102.0
    )
    assert engine.last_unknown_alert_time["CAM-001"] == 102.0
    first_session = first_alerts[0].roi_session_id

    # t=105.0 (<= max_inactive_seconds=10s, cùng vị trí): Track 2 kế thừa Track 1 -> gửi IDENTITY_UPDATE nâng cấp sự cố cũ
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="USER-001",
        matched_name="Minh Nguyen",
        is_authorized=False,
        access_reason="Level 1 khong co quyen vao Area 2",
        access_checked=True
    )
    engine.associate_face(2, face_info)
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(2, bbox)],
        camera_code="CAM-001",
        current_time=105.0
    )
    assert len(alerts) == 1
    assert alerts[0].event_type == "IDENTITY_UPDATE"
    assert alerts[0].identity_outcome == "UNAUTHORIZED"
    assert alerts[0].roi_session_id == first_session

    # t=106.0 (vẫn trong unknown_alert_cooldown=30s kể từ t=102.0): Người khác (Track 3) không có quyền cùng xuất hiện ->
    # cảnh báo UNAUTHORIZED_PERSON VẪN PHẢI BẮN ngay, không bị chặn bởi cooldown UNKNOWN!
    bbox_other = BoundingBox(400, 10, 500, 200, confidence=0.9)
    other_face = FaceDetectionResult(
        bbox=bbox_other,
        score=0.95,
        matched_code="USER-002",
        matched_name="Nguoi Khac",
        is_authorized=False,
        access_reason="Level 1 khong co quyen vao Area 2",
        access_checked=True
    )
    engine.associate_face(3, other_face)
    _, other_alerts, _ = engine.process_frame(
        detected_tracks=[(2, bbox), (3, bbox_other)],
        camera_code="CAM-001",
        current_time=106.0
    )
    assert len(other_alerts) == 1
    assert other_alerts[0].event_type == "UNAUTHORIZED_PERSON"


def test_g3_1_stranger_b_not_silenced_when_identity_not_safe():
    """
    G3.1: Người lạ A bị cảnh báo và rời đi.
    Người lạ B vào trong vòng 2.5s (< 500px) nhưng không an toàn về danh tính (identity_safe=False do có người khác trong frame).
    BẮT BUỘC: Track B KHÔNG được kế thừa alert_unknown_sent=True hoặc cooldown của A.
    Sau khi hết grace period của B, B PHẢI được cảnh báo UNKNOWN_PERSON (không bị im lặng suốt 30s).
    """
    engine = FrameAnalysisEngine(
        unknown_alert_cooldown=30.0,
        access_grace_period=1.5,
        camera_unknown_min_interval=0.0
    )
    polygon = RoiPolygonConfig(
        label="Vùng Cấm",
        vertices=[Point(0.0, 0.0), Point(0.5, 0.0), Point(0.5, 0.5), Point(0.0, 0.5)]
    )

    bbox_a = BoundingBox(100, 100, 200, 300, confidence=0.9)
    bbox_other = BoundingBox(800, 800, 900, 950, confidence=0.9)

    # t=100.0: Người lạ A (Track 1 trong ROI) và người khác (Track 99 ngoài ROI) cùng xuất hiện (visible_count=2)
    engine.process_frame(
        detected_tracks=[(1, bbox_a), (99, bbox_other)],
        roi_polygons=[polygon],
        camera_code="CAM-001",
        current_time=100.0,
        frame_size=(1000, 1000)
    )

    # t=101.6: Hết 1.5s grace -> Chỉ Track 1 bị cảnh báo UNKNOWN_PERSON (Track 99 ngoài ROI)
    _, alerts1, _ = engine.process_frame(
        detected_tracks=[(1, bbox_a), (99, bbox_other)],
        roi_polygons=[polygon],
        camera_code="CAM-001",
        current_time=101.6,
        frame_size=(1000, 1000)
    )
    assert len(alerts1) == 1
    assert alerts1[0].track_id == 1
    assert engine.active_tracks[1].alert_unknown_sent is True

    # t=102.0: Track 1 rời đi, chỉ còn Track 99
    engine.process_frame(
        detected_tracks=[(99, bbox_other)],
        roi_polygons=[polygon],
        camera_code="CAM-001",
        current_time=102.0,
        frame_size=(1000, 1000)
    )

    # t=103.0: 1.0s sau (trong khoảng <= 2.5s), Người lạ B (Track 2) xuất hiện trong ROI cách A 200px (IoU=0)
    # Vì Track 99 vẫn trong frame (visible_count=2) và IoU <= 0.30 -> identity_safe = False
    bbox_b = BoundingBox(300, 100, 400, 300, confidence=0.9)
    engine.process_frame(
        detected_tracks=[(2, bbox_b), (99, bbox_other)],
        roi_polygons=[polygon],
        camera_code="CAM-001",
        current_time=103.0,
        frame_size=(1000, 1000)
    )

    p2 = engine.active_tracks[2]
    # G3.1 Fix verification: Track 2 không được kế thừa alert_unknown_sent và cooldown của Track 1
    assert p2.alert_unknown_sent is False, "Track 2 không được mang cờ alert_unknown_sent=True khi identity_safe=False"
    assert 2 not in engine.track_last_unknown_alert_time, "Track 2 không được mang cooldown của Track 1"

    # t=104.6: Sau 1.6s (> 1.5s grace period của B kể từ t=103.0) -> B PHẢI được cảnh báo UNKNOWN_PERSON!
    _, alerts2, _ = engine.process_frame(
        detected_tracks=[(2, bbox_b), (99, bbox_other)],
        roi_polygons=[polygon],
        camera_code="CAM-001",
        current_time=104.6,
        frame_size=(1000, 1000)
    )
    assert len(alerts2) == 1, "Người lạ B phải được phát cảnh báo, không bị im lặng bởi cooldown của A"
    assert alerts2[0].track_id == 2


if __name__ == "__main__":
    test_track_retention_max_inactive_seconds()
    test_unknown_person_camera_cooldown()
    test_unauthorized_face_not_blocked_by_unknown_cooldown()
    test_g3_1_stranger_b_not_silenced_when_identity_not_safe()
    print("ALL TESTS PASSED!")
