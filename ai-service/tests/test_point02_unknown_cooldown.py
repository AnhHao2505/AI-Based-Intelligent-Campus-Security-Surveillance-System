import pytest
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point


def test_different_unknown_tracks_not_blocked_by_long_cooldown():
    """
    Điểm 2: Cooldown tính theo track_id kết hợp giới hạn tần suất toàn camera.
    Track 1 bắn cảnh báo tại t=2.0.
    Track 2 xuất hiện tại t=2.0, đến t=6.0 (> camera_min_interval 3.0s nhưng < cooldown 30s)
    phải BẮN ĐƯỢC CẢNH BÁO cho Track 2, không bị Track 1 chặn suốt 30s.
    """
    engine = FrameAnalysisEngine(
        unknown_alert_cooldown=30.0,
        camera_unknown_min_interval=3.0
    )
    bbox1 = BoundingBox(10, 10, 50, 100)
    bbox2 = BoundingBox(100, 100, 150, 200)

    # Frame 1: t=0.0 (Track 1 và Track 2 cùng xuất hiện)
    engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=0.0
    )

    # Frame 2: t=2.0 (> 1.5s grace period)
    # Track 1 được xử lý trước -> Bắn cảnh báo
    # Track 2 bị hoãn lại do camera_min_interval (tránh flood trong cùng frame)
    _, alerts_t2, _ = engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=2.0
    )
    assert len(alerts_t2) == 1
    assert alerts_t2[0].track_id == 1
    assert alerts_t2[0].event_type == "UNKNOWN_PERSON"

    # Frame 3: t=3.0 (camera_min_interval = 3.0s -> 3.0 - 2.0 = 1.0s < 3.0s -> vẫn rate limit)
    _, alerts_t3, _ = engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=3.0
    )
    assert len(alerts_t3) == 0

    # Frame 4: t=6.0 (6.0 - 2.0 = 4.0s >= camera_min_interval 3.0s; nhưng 6.0 < 30.0s)
    # Track 2 PHẢI bắn cảnh báo! (Với logic cũ theo camera thì Track 2 bị bỏ sót đến t=32)
    _, alerts_t6, _ = engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=6.0
    )
    assert len(alerts_t6) == 1
    assert alerts_t6[0].track_id == 2
    assert alerts_t6[0].event_type == "UNKNOWN_PERSON"


def test_same_track_honors_track_cooldown():
    """
    Cùng một track_id không được bắn cảnh báo liên tục trước khi hết track cooldown (30s).
    """
    engine = FrameAnalysisEngine(
        unknown_alert_cooldown=30.0,
        camera_unknown_min_interval=3.0
    )
    bbox = BoundingBox(10, 10, 50, 100)

    # t=0: first seen
    engine.process_frame(detected_tracks=[(1, bbox)], camera_code="CAM-001", current_time=0.0)

    # t=2: alert fired
    _, alerts_t2, _ = engine.process_frame(detected_tracks=[(1, bbox)], camera_code="CAM-001", current_time=2.0)
    assert len(alerts_t2) == 1

    # t=15: 13s sau (< 30s) -> không được bắn lại cho Track 1
    _, alerts_t15, _ = engine.process_frame(detected_tracks=[(1, bbox)], camera_code="CAM-001", current_time=15.0)
    assert len(alerts_t15) == 0

    # t=35: 33s sau (> 30s) -> được bắn lại cảnh báo nhắc nhở cho Track 1
    _, alerts_t35, _ = engine.process_frame(detected_tracks=[(1, bbox)], camera_code="CAM-001", current_time=35.0)
    assert len(alerts_t35) == 1
    assert alerts_t35[0].track_id == 1
def test_track_unknown_alert_time_cleaned_on_track_deletion():
    """
    Xác nhận track_last_unknown_alert_time được dọn dẹp khi track bị xóa khỏi active_tracks.
    """
    engine = FrameAnalysisEngine(max_inactive_seconds=2.0)
    bbox = BoundingBox(10, 10, 50, 100)

    # Frame 1 & 2: Track 1 xuất hiện và bắn cảnh báo tại t=2.0
    engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=0.0)
    engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=2.0)

    assert 1 in engine.active_tracks
    assert 1 in engine.track_last_unknown_alert_time
    assert engine.track_last_unknown_alert_time[1] == 2.0

    # Frame 3: Track 1 biến mất trong 5s (> max_inactive_seconds 2.0s)
    engine.process_frame([], camera_code="CAM-001", current_time=7.0)

    # Track 1 phải bị dọn khỏi active_tracks VÀ track_last_unknown_alert_time
    assert 1 not in engine.active_tracks
    assert 1 not in engine.track_last_unknown_alert_time


def test_track_unknown_alert_time_migrated_on_id_switch():
    """
    Khi ByteTrack switch ID (Track 1 biến mất, Track 2 kế thừa),
    thời gian cảnh báo được chuyển sang Track 2 và Track 1 được dọn dẹp.
    """
    engine = FrameAnalysisEngine(max_inactive_seconds=2.0)
    bbox1 = BoundingBox(100, 100, 200, 300)
    bbox2 = BoundingBox(102, 101, 202, 301)

    # Track 1 bắn cảnh báo tại t=2.0
    engine.process_frame([(1, bbox1)], camera_code="CAM-001", current_time=0.0)
    engine.process_frame([(1, bbox1)], camera_code="CAM-001", current_time=2.0)
    assert 1 in engine.track_last_unknown_alert_time

    # ID switch tại t=2.2: Track 1 biến mất, Track 2 xuất hiện tại cùng vị trí
    engine.process_frame([(2, bbox2)], camera_code="CAM-001", current_time=2.2)

    # Track 1 phải được dọn dẹp khỏi track_last_unknown_alert_time, Track 2 kế thừa timestamp
    assert 1 not in engine.track_last_unknown_alert_time
    assert 2 in engine.track_last_unknown_alert_time
    assert engine.track_last_unknown_alert_time[2] == 2.0
