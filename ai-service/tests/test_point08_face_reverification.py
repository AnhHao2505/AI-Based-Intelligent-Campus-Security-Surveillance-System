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


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_identity_switch_calls_exit_then_entry_sequentially_in_one_task(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-08 (Đợt 4.6): Khi đổi danh tính A sang B:
    - EXIT(A) rồi ENTRY(B) phải chạy tuần tự trong MỘT task duy nhất.
    - Không để hai task chạy song song có nguy cơ ENTRY(B) trước EXIT(A).
    - Test kiểm tra thứ tự lời gọi bằng mock call args.
    """
    import time
    pipeline = VideoPipeline(camera_code="CAM-POINT08-SEQ", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
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

    # Bước 1: Khởi tạo track với USER_A qua cache để entry logged = True đồng bộ
    pipeline.face_matcher.get_cached_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.70, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=0.0)
    p = pipeline.analysis_engine.active_tracks.get(1)
    assert p is not None
    assert p.roi_entry_logged is True
    pipeline.face_matcher.get_cached_access.return_value = None

    # Theo dõi thứ tự các cuộc gọi tới check_camera_access
    call_log = []
    def record_access_call(cam, code, roi_label=None, event_direction=None, skip_cache=False):
        call_log.append((code, event_direction))
        return (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")

    pipeline.face_matcher.check_camera_access.side_effect = record_access_call
    pipeline.face_matcher.check_camera_access.reset_mock()
    call_log.clear()

    # Bước 2: Match USER_B lần 1 (score 0.85) -> Chưa đổi
    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.85, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=3.5)
    assert len(call_log) == 0, "Lần 1 chưa được đổi danh tính hoặc gọi EXIT/ENTRY"

    # Bước 3: Match USER_B lần 2 (score 0.88) -> Kích hoạt đổi danh tính!
    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.88, True, "NORMAL_USER")
    pipeline.process_frame(frame, current_time=7.0)

    # Đợi task trong access_executor hoàn tất
    time.sleep(0.3)

    # Kiểm tra thứ tự lời gọi: EXIT(USER_A) phải chạy trước ENTRY(USER_B)
    assert len(call_log) == 2, f"Kỳ vọng 2 lời gọi (EXIT và ENTRY), thực tế: {call_log}"
    assert call_log[0] == ("USER_A", "EXIT"), f"Lời gọi đầu tiên phải là EXIT(USER_A), thực tế: {call_log[0]}"
    assert call_log[1] == ("USER_B", "ENTRY"), f"Lời gọi thứ hai phải là ENTRY(USER_B), thực tế: {call_log[1]}"
