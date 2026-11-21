"""
Phase 3: Tối ưu AI Service cho Camera PUBLIC_AREA & CORRIDOR.
- Ban ngày (trong giờ hoạt động): Camera Công cộng / Hành lang ngủ 100% ngay đầu process_frame:
  tắt MOG2 MotionDetector, YOLO26 HumanDetector, SCRFD FaceDetector, ArcFace FaceMatcher,
  không phát SUSPICIOUS_MOVING_OBJECT, không gọi check-entry, không ghi CameraAccessLog.
- Ban đêm (after_hours=True): Đánh thức AI để bắt PERSON_INTRUSION + SUSPICIOUS_MOVING_OBJECT,
  nhưng vẫn quét mặt ngầm để miễn trừ cho Role.GUARD được Backend xác nhận (is_authorized=True).
- Camera hạn chế (RESTRICTED_AREA): Giám sát 24/7 cả ban ngày lẫn ban đêm.
"""

import os
import sys
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch
import numpy as np

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.entity import BoundingBox, FaceDetectionResult, Point, MotionResult
from src.core.shared_face_cache import SharedFaceCache, reset_shared_face_cache
from src.pipeline.video_pipeline import VideoPipeline


VN_TZ = timezone(timedelta(hours=7))


def _ts(hour: int) -> float:
    return datetime(2026, 11, 21, hour, 0, 0, tzinfo=VN_TZ).timestamp()


def _make_mocked_pipeline(camera_code: str, is_public: bool) -> VideoPipeline:
    with patch("src.core.face_matcher.FaceMatcher._get_connection"):
        pipeline = VideoPipeline(
            camera_code=camera_code,
            after_hour_start="22:00",
            after_hour_end="06:00",
            enable_motion_gating=True,
        )
    pipeline.face_matcher.is_camera_public = MagicMock(return_value=is_public)
    pipeline.face_matcher.get_camera_area_name = MagicMock(
        return_value="Hành lang Cánh Đông" if is_public else "Phòng Server"
    )
    pipeline.face_matcher.match_face = MagicMock(return_value=None)
    pipeline.face_matcher.get_cached_access = MagicMock(return_value=None)

    pipeline.motion_detector.detect = MagicMock(
        return_value=MotionResult(
            has_motion=True,
            has_motion_full=True,
            has_motion_roi=True,
            motion_area=5000,
            motion_boxes=[BoundingBox(100, 100, 220, 220)],
        )
    )
    pipeline.human_detector.detect_and_track = MagicMock(return_value=[])
    pipeline.face_detector.detect_best_face_in_crop = MagicMock(return_value=None)
    return pipeline


def test_public_and_corridor_camera_sleeps_completely_during_daytime():
    """
    Happy Path (Ban ngày - 14:00): Camera thuộc nhóm publicCameraCodes (PUBLIC_AREA hoặc CORRIDOR)
    phải ngủ 100% ngay đầu process_frame -> 0 lần gọi MOG2, YOLO26, SCRFD, ArcFace, 0 alerts, 0 ROI events.
    """
    pipeline = _make_mocked_pipeline("CAM-CORR-01", is_public=True)
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    day_ts = _ts(14)

    out_frame, persons, alerts = pipeline.process_frame(frame, current_time=day_ts, need_annotated=True)

    assert persons == []
    assert alerts == []
    assert out_frame is not None
    assert pipeline._is_daytime_sleeping is True
    pipeline.motion_detector.detect.assert_not_called()
    pipeline.human_detector.detect_and_track.assert_not_called()
    pipeline.face_detector.detect_best_face_in_crop.assert_not_called()
    pipeline.face_matcher.match_face.assert_not_called()


def test_restricted_camera_stays_awake_24_7_during_daytime():
    """
    Boundary (Ban ngày - 14:00): Camera RESTRICTED_AREA (is_public=False) vẫn phải chạy AI 24/7.
    """
    pipeline = _make_mocked_pipeline("CAM-SERVER-01", is_public=False)
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    day_ts = _ts(14)

    pipeline.process_frame(frame, current_time=day_ts)

    assert pipeline._is_daytime_sleeping is False
    pipeline.motion_detector.detect.assert_called_once()
    pipeline.human_detector.detect_and_track.assert_called_once()


def test_public_and_corridor_camera_wakes_up_at_night_and_exempts_guard():
    """
    Ban đêm (23:00): Camera PUBLIC_AREA / CORRIDOR tự đánh thức:
    - Chạy MOG2, YOLO26, SCRFD + ArcFace ngầm để nhận diện khuôn mặt.
    - Phát SUSPICIOUS_MOVING_OBJECT cho vật thể chuyển động kéo dài.
    - Phát PERSON_INTRUSION cho người lạ / người không phải bảo vệ được phép.
    - Miễn trừ (0 cảnh báo) cho Bảo vệ đang trực (role=GUARD, is_authorized=True).
    """
    pipeline = _make_mocked_pipeline("CAM-CORR-01", is_public=True)
    pipeline.roi_polygon = [Point(50, 50), Point(600, 50), Point(600, 450), Point(50, 450)]
    frame = np.full((480, 640, 3), 120, dtype=np.uint8)

    # 1. Ban ngày ngủ trước
    pipeline.process_frame(frame, current_time=_ts(14))
    assert pipeline._is_daytime_sleeping is True
    pipeline.motion_detector.detect.assert_not_called()

    # 2. Bước sang 23:00 (sau giờ giới nghiêm) -> đánh thức AI & bắt SUSPICIOUS_MOVING_OBJECT
    t_night = _ts(23)
    pipeline.process_frame(frame, current_time=t_night)
    assert pipeline._is_daytime_sleeping is False
    pipeline.motion_detector.detect.assert_called_once()
    pipeline.human_detector.detect_and_track.assert_called_once()

    # Duy trì chuyển động vật thể lạ >= 0.5s -> nổ SUSPICIOUS_MOVING_OBJECT
    _, _, motion_alerts = pipeline.process_frame(frame, current_time=t_night + 0.6)
    assert any(a.event_type == "SUSPICIOUS_MOVING_OBJECT" for a in motion_alerts)

    # 3. Xuất hiện Bảo vệ hợp lệ (GUARD + is_authorized=True) -> KHÔNG phát cảnh báo
    guard_bbox = BoundingBox(150, 100, 300, 400, confidence=0.95)
    pipeline.human_detector.detect_and_track.return_value = [(10, guard_bbox)]
    guard_face = FaceDetectionResult(
        bbox=BoundingBox(190, 110, 260, 180),
        score=0.92,
        matched_code="BV001",
        matched_name="Bảo Vệ Ca Đêm",
        is_authorized=True,
        access_checked=True,
        access_source="ACCESS_LEVEL",
        role="GUARD",
        area_name="Hành lang Cánh Đông",
    )
    pipeline.analysis_engine.associate_face(10, guard_face)
    persons_g, alerts_g = pipeline.process_frame(frame, current_time=t_night + 3.0)[1:3]
    assert alerts_g == []
    assert any(p.track_id == 10 and p.security_display_state == "AUTHORIZED" for p in persons_g)

    # 4. Xuất hiện Người lạ không nhận diện được vào ban đêm -> nổ PERSON_INTRUSION
    stranger_bbox = BoundingBox(350, 100, 500, 400, confidence=0.95)
    pipeline.human_detector.detect_and_track.return_value = [(20, stranger_bbox)]
    pipeline.process_frame(frame, current_time=t_night + 10.0)
    _, _, alerts_s = pipeline.process_frame(frame, current_time=t_night + 13.0)
    assert any(a.event_type == "PERSON_INTRUSION" for a in alerts_s)


def test_shared_face_cache_db_fallback_queries_left_join_for_corridor():
    """SharedFaceCache DB fallback phải dùng LEFT JOIN areas và nạp cả CORRIDOR vào public_camera_codes."""
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)

    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_cur.fetchall.side_effect = [
        [],
        [("CAM-CORR-01", "Hành lang Cánh Đông")],
        [("CAM-CORR-01",), ("CAM-PUB-01",)],
    ]
    mock_conn.cursor.return_value = mock_cur

    with patch.object(cache, "_get_connection", return_value=mock_conn):
        ok = cache.refresh_cache()

    assert ok is True
    assert cache.camera_area_map.get("CAM-CORR-01") == "Hành lang Cánh Đông"
    assert "CAM-CORR-01" in cache.public_camera_codes
    assert "CAM-PUB-01" in cache.public_camera_codes

    executed_sqls = " ".join(str(call.args[0]) for call in mock_cur.execute.call_args_list)
    assert "LEFT JOIN areas" in executed_sqls
    assert "CORRIDOR" in executed_sqls
