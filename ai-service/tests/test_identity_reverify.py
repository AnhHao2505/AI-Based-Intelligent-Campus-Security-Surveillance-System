"""
Quét lại mặt người đã nhận diện (Điểm 8) và gỡ danh tính kế thừa.
1) Giãn cách quét lại tính theo lần THỬ quét lại, không chỉ lần khớp: lần thử không khớp không được biến thành
   "quét mọi frame".
2) Danh tính kế thừa chỉ bị gỡ khi 2 lần quét lại ra điểm rõ là người khác (< FACE_REVOKE_MAX_SCORE).
   Vùng mơ hồ (từ FACE_REVOKE_MAX_SCORE tới dưới ngưỡng khớp) không tính, không reset.
"""
from unittest.mock import patch

import numpy as np

from src.config import settings
from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.pipeline.video_pipeline import VideoPipeline

PATCHES = (
    "src.pipeline.video_pipeline.HumanDetector",
    "src.pipeline.video_pipeline.FaceDetector",
    "src.pipeline.video_pipeline.FaceMatcher",
    "src.pipeline.video_pipeline.StorageService",
    "src.pipeline.video_pipeline.get_security_mqtt_client",
)


def _make_pipeline():
    pipeline = VideoPipeline(camera_code="CAM-REVERIFY-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    m = pipeline.face_matcher
    m.get_cached_access.return_value = None
    m.get_camera_area_name.return_value = "Khu vực test"
    m.is_camera_public.return_value = False
    m.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_LEVEL")
    pipeline.human_detector.detect_and_track.return_value = [(1, BoundingBox(100.0, 100.0, 300.0, 500.0))]
    _face_visible(pipeline, True)
    return pipeline


def _face_visible(pipeline, visible: bool):
    # Mỗi lần dò trả object MỚI như bộ dò thật (pipeline gắn matched_code lên chính object kết quả)
    pipeline.face_detector.detect_best_face_in_crop.side_effect = (
        (lambda *a, **k: FaceDetectionResult(bbox=BoundingBox(120.0, 110.0, 220.0, 210.0), score=0.90))
        if visible else (lambda *a, **k: None)
    )


FRAME = np.full((600, 800, 3), 120, dtype=np.uint8)


def _identify(pipeline, score=0.55):
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", score, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.0)
    p = pipeline.analysis_engine.active_tracks.get(1)
    assert p is not None and p.face_info.matched_code == "USER_A"
    return p


def _with_patches(test):
    for target in PATCHES:
        test = patch(target)(test)
    return test


@_with_patches
def test_failed_reverify_attempt_waits_full_interval_instead_of_every_frame(*_mocks):
    pipeline = _make_pipeline()
    _identify(pipeline, score=0.55)  # < 0.82 nên có quét lại định kỳ
    det = pipeline.face_detector.detect_best_face_in_crop

    # t=3.5: tới hạn quét lại, nhưng không thấy mặt (quay đi)
    _face_visible(pipeline, False)
    det.reset_mock()
    pipeline.process_frame(FRAME, current_time=3.5)
    assert det.call_count == 1

    # t=3.6 .. 6.4: chưa đủ 3 s kể từ lần THỬ ở t=3.5 -> không quét (code cũ quét mọi frame ở đây)
    det.reset_mock()
    for t in (3.6, 4.0, 5.0, 6.4):
        pipeline.process_frame(FRAME, current_time=t)
    assert det.call_count == 0

    # t=6.6: đủ 3 s -> thử lại
    pipeline.process_frame(FRAME, current_time=6.6)
    assert det.call_count == 1


@_with_patches
def test_high_score_identity_is_not_reverified(*_mocks):
    pipeline = _make_pipeline()
    _identify(pipeline, score=0.90)  # >= 0.82
    det = pipeline.face_detector.detect_best_face_in_crop
    det.reset_mock()
    for t in (3.5, 7.0, 10.5):
        pipeline.process_frame(FRAME, current_time=t)
    assert det.call_count == 0


@_with_patches
def test_new_track_inheriting_high_score_parent_forces_reverify_on_first_frame(*_mocks):
    """
    G3.5: Track 1 ban đầu có điểm cao (0.90 >= 0.82), hợp lệ.
    Track 1 mất dấu, Track 2 xuất hiện ở cùng vị trí (IoU/khoảng cách gần).
    Bắt buộc phải kích hoạt kiểm tra khuôn mặt ngay ở frame đầu tiên của Track 2 (reverify_due = True),
    không được mượn trọn vẹn danh tính mà bỏ qua nhận diện.
    """
    pipeline = _make_pipeline()
    _identify(pipeline, score=0.90)
    det = pipeline.face_detector.detect_best_face_in_crop

    # Track 1 biến mất, Track 2 xuất hiện ở cùng vị trí
    bbox2 = BoundingBox(102.0, 102.0, 302.0, 502.0)
    pipeline.human_detector.detect_and_track.return_value = [(2, bbox2)]
    det.reset_mock()

    # t=1.0: Frame đầu tiên của Track 2
    pipeline.process_frame(FRAME, current_time=1.0)
    assert det.call_count == 1, "Track mới mượn danh tính cha phải bị quét mặt ngay frame 1!"


@_with_patches
def test_new_track_inheriting_parent_clears_inheritance_when_same_identity_verified(*_mocks):
    """
    Track 2 mượn danh tính cha và được nhận diện đúng là USER_A ngay ở frame 1:
    Track 2 phải được xóa cờ kế thừa (is_inherited_identity = False).
    """
    pipeline = _make_pipeline()
    _identify(pipeline, score=0.90)

    # Track 2 xuất hiện, match tiếp USER_A
    bbox2 = BoundingBox(102.0, 102.0, 302.0, 502.0)
    pipeline.human_detector.detect_and_track.return_value = [(2, bbox2)]
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.91, True, "NORMAL_USER")

    pipeline.process_frame(FRAME, current_time=1.0)
    p2 = pipeline.analysis_engine.active_tracks.get(2)
    assert p2 is not None
    assert p2.face_info.matched_code == "USER_A"
    assert p2.is_inherited_identity is False


@_with_patches
def test_inherited_identity_kept_in_ambiguous_zone_and_revoked_when_clearly_someone_else(*_mocks):
    pipeline = _make_pipeline()
    p = _identify(pipeline)
    p.is_inherited_identity = True
    pipeline.face_matcher.match_face.return_value = None

    # Vùng mơ hồ 0.30–0.50 (người thật nghiêng mặt): giữ danh tính, không tính
    pipeline.face_matcher.last_best_score = 0.40
    for t in (0.1, 0.2, 0.3):
        pipeline.process_frame(FRAME, current_time=t)
    assert p.face_info.matched_code == "USER_A"
    assert p.inherited_mismatch_count == 0

    # Rõ là người khác (< 0.30): lần 1 chưa gỡ, lần 2 gỡ
    pipeline.face_matcher.last_best_score = 0.20
    pipeline.process_frame(FRAME, current_time=0.4)
    assert p.face_info.matched_code == "USER_A"
    assert p.inherited_mismatch_count == 1
    pipeline.process_frame(FRAME, current_time=0.5)
    assert not p.face_info.matched_code
    assert p.is_inherited_identity is False
    # Còn trong thời gian chờ xác thực tính từ lúc vào ROI (t=0): đang xác thực lại, chưa báo người lạ
    assert p.security_display_state == "VERIFYING"

    # Hết grace nhưng vẫn đang thấy mặt (chưa khớp) -> giữ thêm (hold), chưa báo người lạ
    t_after_grace = settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS + 0.2
    pipeline.process_frame(FRAME, current_time=t_after_grace)
    assert p.security_display_state == "VERIFYING"

    # Quá cả thời gian giữ thêm mà vẫn không khớp ai -> người lạ
    t_after_hold = settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS + settings.ACCESS_RESOLUTION_HOLD_SECONDS + 0.2
    pipeline.process_frame(FRAME, current_time=t_after_hold)
    assert not p.face_info.matched_code
    assert p.security_display_state == "UNKNOWN"


@_with_patches
def test_ambiguous_frame_between_clear_mismatches_does_not_reset_counter(*_mocks):
    pipeline = _make_pipeline()
    p = _identify(pipeline)
    p.is_inherited_identity = True
    pipeline.face_matcher.match_face.return_value = None
    for t, sc in ((0.1, 0.20), (0.2, 0.45), (0.3, 0.15)):
        pipeline.face_matcher.last_best_score = sc
        pipeline.process_frame(FRAME, current_time=t)
    assert not p.face_info.matched_code


@_with_patches
def test_match_same_identity_resets_mismatch_counter_and_inheritance(*_mocks):
    pipeline = _make_pipeline()
    p = _identify(pipeline)
    p.is_inherited_identity = True
    pipeline.face_matcher.match_face.return_value = None
    pipeline.face_matcher.last_best_score = 0.20
    pipeline.process_frame(FRAME, current_time=0.1)
    assert p.inherited_mismatch_count == 1

    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.60, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.2)
    assert p.inherited_mismatch_count == 0
    assert p.is_inherited_identity is False
    assert p.face_info.matched_code == "USER_A"


@_with_patches
def test_self_identified_track_is_never_revoked(*_mocks):
    pipeline = _make_pipeline()
    p = _identify(pipeline)  # tự nhận diện trên chính track này, không kế thừa
    pipeline.face_matcher.match_face.return_value = None
    pipeline.face_matcher.last_best_score = 0.05
    for t in (3.5, 7.0, 10.5, 14.0):
        pipeline.process_frame(FRAME, current_time=t)
    assert p.face_info.matched_code == "USER_A"


def test_revoke_threshold_is_configurable():
    assert 0.0 < settings.FACE_REVOKE_MAX_SCORE < settings.FACE_MATCH_THRESHOLD
