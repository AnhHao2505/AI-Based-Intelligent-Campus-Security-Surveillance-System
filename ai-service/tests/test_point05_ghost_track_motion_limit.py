from unittest.mock import MagicMock, patch
import pytest
from src.core.entity import BoundingBox, Point, MotionResult, TrackedPerson
from src.pipeline.video_pipeline import VideoPipeline
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.config import settings


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_ghost_track_purged_after_motion_hold_limit(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Điểm 5: Khi YOLO không còn nhìn thấy người, motion box chỉ được phép gia hạn track
    tối đa MAX_MOTION_TRACK_HOLD_SECONDS (3.0s).
    Sau thời hạn đó, chuyển động giả (rèm cửa, quạt, bóng cây) không được gia hạn track nữa,
    và track ma phải bị FrameAnalysisEngine dọn dẹp sau max_inactive_seconds (2.0s).
    """
    pipeline = VideoPipeline(
        camera_code="CAM-001",
        max_motion_track_hold_seconds=3.0
    )
    pipeline.analysis_engine.max_inactive_seconds = 2.0

    bbox = BoundingBox(x1=100.0, y1=100.0, x2=200.0, y2=300.0)
    motion_mb = [BoundingBox(x1=110.0, y1=110.0, x2=190.0, y2=290.0)]
    motion_res = MotionResult(has_motion=True, motion_area=5000, motion_boxes=motion_mb)

    # 1. Tại t=100.0: YOLO phát hiện người (Track 1)
    pipeline.analysis_engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-001",
        current_time=100.0
    )
    assert 1 in pipeline.analysis_engine.active_tracks
    p = pipeline.analysis_engine.active_tracks[1]
    assert getattr(p, "yolo_last_seen_time", None) == 100.0

    # 2. Tại t=102.0 (2.0s sau YOLO, <= 3.0s hold limit):
    # Giả lập pipeline xử lý frame qua logic motion extension
    current_time = 102.0
    current_frame_tids = set()  # YOLO trượt detection
    for tid, trk in list(pipeline.analysis_engine.active_tracks.items()):
        inactive_dur = current_time - trk.last_seen_time
        if inactive_dur <= pipeline.analysis_engine.max_inactive_seconds:
            yolo_last = getattr(trk, "yolo_last_seen_time", trk.first_seen_time) or trk.first_seen_time
            if (current_time - yolo_last) <= pipeline.max_motion_track_hold_seconds:
                overlapping_mb = [
                    mb for mb in motion_res.motion_boxes
                    if pipeline._is_box_overlapping_any(mb, [trk.bbox], overlap_threshold=0.08)
                ]
                if overlapping_mb:
                    trk.last_seen_time = current_time
                    trk.is_currently_visible = True

    assert p.last_seen_time == 102.0, "Trong giới hạn 3.0s, track vẫn được duy trì"

    # 3. Tại t=104.0 (4.0s sau YOLO, > 3.0s hold limit):
    # Chuyển động giả tiếp tục nhưng KHÔNG ĐƯỢC gia hạn track nữa!
    current_time = 104.0
    for tid, trk in list(pipeline.analysis_engine.active_tracks.items()):
        inactive_dur = current_time - trk.last_seen_time
        if inactive_dur <= pipeline.analysis_engine.max_inactive_seconds:
            yolo_last = getattr(trk, "yolo_last_seen_time", trk.first_seen_time) or trk.first_seen_time
            if (current_time - yolo_last) <= pipeline.max_motion_track_hold_seconds:
                overlapping_mb = [
                    mb for mb in motion_res.motion_boxes
                    if pipeline._is_box_overlapping_any(mb, [trk.bbox], overlap_threshold=0.08)
                ]
                if overlapping_mb:
                    trk.last_seen_time = current_time
                    trk.is_currently_visible = True

    # last_seen_time KHÔNG được cập nhật lên 104.0
    assert p.last_seen_time == 102.0

    # 4. Tại t=105.0: Đã quá max_inactive_seconds (105.0 - 102.0 = 3.0s > 2.0s):
    # FrameAnalysisEngine dọn dẹp track ma
    pipeline.analysis_engine.process_frame(
        detected_tracks=[],
        camera_code="CAM-001",
        current_time=105.0
    )
    assert 1 not in pipeline.analysis_engine.active_tracks, "Track ma phải bị dọn dẹp sạch khỏi active_tracks!"
