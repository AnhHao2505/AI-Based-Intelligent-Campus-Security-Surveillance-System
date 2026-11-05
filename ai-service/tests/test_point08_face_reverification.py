from unittest.mock import MagicMock, patch
import numpy as np
import pytest

from src.pipeline.video_pipeline import VideoPipeline
from src.core.entity import FaceDetectionResult, BoundingBox, Point


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_reverification_requires_two_consecutive_matches_to_switch_identity(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-08 (a, b, c):
    - Khi mã mới KHÁC danh tính hiện tại, yêu cầu đúng 2 lần match liên tiếp cùng mã mới trước khi đổi.
    - Lần 1: Không đổi, giữ nguyên danh tính cũ.
    - Lần 2 liên tiếp: Đổi danh tính, reset cảnh báo, ghi EXIT mã cũ và ENTRY mã mới.
    """
    pipeline = VideoPipeline(camera_code="CAM-POINT08-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    pipeline.face_matcher.get_cached_access.return_value = None
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)

    face_crop_result = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0),
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_result
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

    # 1. Tại t=0.0s: Match ban đầu USER_A, điểm 0.75
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.75, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=0.0)

    p = pipeline.analysis_engine.active_tracks.get(1)
    assert p is not None
    assert p.face_info.matched_code == "USER_A"
    assert p.face_info.score == 0.75
    p.roi_entry_logged = True
    p.alert_unauthorized_sent = True

    # 2. Tại t=3.5s: Lần 1 match USER_B (score 0.90) -> KHÔNG ĐỔI! Vẫn giữ USER_A
    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.90, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=3.5)
    assert p.face_info.matched_code == "USER_A", "Match 1 lần khác mã không được đổi danh tính!"
    assert p.alert_unauthorized_sent is True

    # 3. Tại t=7.0s: Lần 2 liên tiếp match USER_B (score 0.92) -> ĐỔI THÀNH USER_B!
    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.92, True, "NORMAL_USER")
    pipeline.face_matcher.check_camera_access.reset_mock()
    pipeline.process_frame(frame, current_time=7.0)

    assert p.face_info.matched_code == "USER_B", "2 lần liên tiếp cùng mã mới phải đổi sang USER_B!"
    assert p.face_info.score == 0.92
    assert p.alert_unauthorized_sent is False, "Đổi danh tính phải reset cảnh báo vi phạm"


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_reverification_single_match_then_old_identity_does_not_switch(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-08: A thành B 1 lần rồi lại match A -> giữ nguyên A, không đổi sang B.
    """
    pipeline = VideoPipeline(camera_code="CAM-POINT08-TEST", enable_motion_gating=False)
    pipeline.face_matcher.get_cached_access.return_value = None
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)

    face_crop_result = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0),
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_result
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

    # Match ban đầu USER_A
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.75, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=0.0)
    p = pipeline.analysis_engine.active_tracks.get(1)

    # Lần 1: match USER_B
    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.88, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=3.5)
    assert p.face_info.matched_code == "USER_A"

    # Lần 2: lại match USER_A -> reset candidate, giữ nguyên USER_A
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.78, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=7.0)
    assert p.face_info.matched_code == "USER_A"
    assert getattr(p, "pending_reverify_code", None) is None


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_reverification_same_identity_higher_score_updates_score_without_resetting_alerts(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-08: Cùng mã mà điểm cao hơn chỉ cập nhật điểm, KHÔNG reset cảnh báo.
    """
    pipeline = VideoPipeline(camera_code="CAM-POINT08-TEST", enable_motion_gating=False)
    pipeline.face_matcher.get_cached_access.return_value = None
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)

    face_crop_result = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0),
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_result
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

    # Match ban đầu USER_A, điểm 0.75
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.75, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=0.0)
    p = pipeline.analysis_engine.active_tracks.get(1)
    p.alert_unauthorized_sent = True

    # Cùng mã USER_A nhưng điểm cao hơn 0.88
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.88, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=3.5)

    assert p.face_info.matched_code == "USER_A"
    assert p.face_info.score == 0.88
    assert p.alert_unauthorized_sent is True, "Cùng mã điểm cao hơn KHÔNG được reset cảnh báo!"
