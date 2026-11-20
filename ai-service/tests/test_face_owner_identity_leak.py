"""
Người chưa có quyền đứng chồng lên người có quyền không được "mượn" danh tính của người đó.
1) Mặt trong vùng quét của người A nhưng thuộc người B đứng chồng lên -> không dùng để định danh A.
2) Dọn trùng track: chép danh tính từ track đang mất dấu sang track đang thấy phải là KẾ THỪA (còn gỡ được),
   và không dùng chung object face_info.
3) Track cha khớp chỉ theo khoảng cách khi trong frame còn người khác -> không chuyển danh tính.
4) Danh tính kế thừa được đổi sang mã khớp trên chính track mà không phải vượt điểm của track cha.
"""
import unittest
from unittest.mock import MagicMock, patch

import numpy as np

from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.core.face_detector import FaceDetector
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline

PATCHES = (
    "src.pipeline.video_pipeline.HumanDetector",
    "src.pipeline.video_pipeline.FaceDetector",
    "src.pipeline.video_pipeline.FaceMatcher",
    "src.pipeline.video_pipeline.StorageService",
    "src.pipeline.video_pipeline.get_security_mqtt_client",
)

FRAME = np.full((600, 800, 3), 120, dtype=np.uint8)

# Người đứng trước, đang cúi (track 1) và người cao hơn đứng phía sau (track 2)
FRONT_BOX = BoundingBox(100.0, 130.0, 300.0, 550.0)
BEHIND_BOX = BoundingBox(220.0, 100.0, 420.0, 550.0)
# Mặt người phía sau: nằm trong vùng quét (60% trên) của người trước, nhưng sát đỉnh-giữa khung người sau
BEHIND_FACE = BoundingBox(270.0, 115.0, 330.0, 185.0)
FRONT_FACE = BoundingBox(170.0, 140.0, 230.0, 210.0)


def _with_patches(test):
    for target in PATCHES:
        test = patch(target)(test)
    return test


def _make_pipeline():
    pipeline = VideoPipeline(camera_code="CAM-FACE-OWNER-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    m = pipeline.face_matcher
    m.get_cached_access.return_value = None
    m.get_camera_area_name.return_value = "Khu vực test"
    m.is_camera_public.return_value = False
    m.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_LEVEL")
    return pipeline


def _detector_returning(faces):
    """Giả lập SCRFD: trả mặt điểm cao nhất trong số các mặt nằm trong crop và qua face_filter."""
    def _detect(crop, offset_xy=(0, 0), face_filter=None):
        ox, oy = offset_xy
        h, w = crop.shape[:2]
        found = []
        for fb, sc in faces:
            cx, cy = (fb.x1 + fb.x2) / 2.0, (fb.y1 + fb.y2) / 2.0
            if ox <= cx <= ox + w and oy <= cy <= oy + h:
                found.append(FaceDetectionResult(bbox=fb, score=sc))
        if face_filter is not None:
            found = [f for f in found if face_filter(f)]
        if not found:
            return None
        return max(found, key=lambda f: f.score)
    return _detect


@_with_patches
def test_face_of_person_behind_is_not_used_for_person_in_front(*_mocks):
    pipeline = _make_pipeline()
    pipeline.human_detector.detect_and_track.return_value = [(1, FRONT_BOX), (2, BEHIND_BOX)]
    # Người trước cúi mặt: chỉ thấy mặt người sau
    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detector_returning([(BEHIND_FACE, 0.95)])
    pipeline.face_matcher.match_face.return_value = ("USER_GA", "Phan Gia Anh", 0.90, True, "NORMAL_USER")

    for t in (0.0, 0.3, 0.6):
        pipeline.process_frame(FRAME, current_time=t)

    front = pipeline.analysis_engine.active_tracks.get(1)
    behind = pipeline.analysis_engine.active_tracks.get(2)
    assert behind is not None and behind.face_info.matched_code == "USER_GA"
    assert front is not None
    assert not (front.face_info and front.face_info.matched_code)


@_with_patches
def test_person_in_front_uses_own_face_when_both_faces_visible(*_mocks):
    pipeline = _make_pipeline()
    pipeline.human_detector.detect_and_track.return_value = [(1, FRONT_BOX), (2, BEHIND_BOX)]
    # Mặt người sau rõ hơn (điểm cao hơn) nhưng người trước vẫn phải được quét bằng mặt của chính mình
    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detector_returning(
        [(BEHIND_FACE, 0.95), (FRONT_FACE, 0.70)]
    )
    pipeline.face_matcher.match_face.return_value = None
    pipeline.face_matcher.last_best_score = 0.10
    pipeline.process_frame(FRAME, current_time=0.0)

    front = pipeline.analysis_engine.active_tracks.get(1)
    assert front is not None and front.face_info is not None
    assert front.face_info.bbox == FRONT_FACE


@_with_patches
def test_single_person_face_detection_is_unchanged(*_mocks):
    pipeline = _make_pipeline()
    pipeline.human_detector.detect_and_track.return_value = [(1, FRONT_BOX)]
    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detector_returning([(FRONT_FACE, 0.80)])
    pipeline.face_matcher.match_face.return_value = ("USER_M", "Nguyen Hoang Minh", 0.70, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.0)
    assert pipeline.analysis_engine.active_tracks[1].face_info.matched_code == "USER_M"
    # Không có ai chồng lên -> gọi bộ dò như cũ, không truyền face_filter
    _, kwargs = pipeline.face_detector.detect_best_face_in_crop.call_args
    assert "face_filter" not in kwargs


def test_is_face_owned_by_prefers_person_whose_head_matches():
    pipeline = VideoPipeline.__new__(VideoPipeline)
    assert pipeline._is_face_owned_by(BEHIND_FACE, BEHIND_BOX, [FRONT_BOX]) is True
    assert pipeline._is_face_owned_by(BEHIND_FACE, FRONT_BOX, [BEHIND_BOX]) is False
    assert pipeline._is_face_owned_by(FRONT_FACE, FRONT_BOX, [BEHIND_BOX]) is True
    # Người kia không chứa mặt trong vùng quét của họ -> không phải đối thủ
    far_box = BoundingBox(600.0, 100.0, 780.0, 550.0)
    assert pipeline._is_face_owned_by(BEHIND_FACE, FRONT_BOX, [far_box]) is True


@_with_patches
def test_inherited_identity_switches_to_own_match_without_beating_parent_score(*_mocks):
    pipeline = _make_pipeline()
    pipeline.human_detector.detect_and_track.return_value = [(1, FRONT_BOX)]
    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detector_returning([(FRONT_FACE, 0.80)])
    pipeline.face_matcher.match_face.return_value = ("USER_GA", "Phan Gia Anh", 0.90, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.0)
    p = pipeline.analysis_engine.active_tracks[1]
    p.is_inherited_identity = True  # danh tính của người khác chuyển sang (điểm 0.90 là của mặt người đó)

    pipeline.face_matcher.match_face.return_value = ("USER_M", "Nguyen Hoang Minh", 0.60, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.1)
    assert pipeline.analysis_engine.active_tracks[1].face_info.matched_code == "USER_GA"  # lần 1: chưa đổi
    pipeline.process_frame(FRAME, current_time=0.2)
    assert pipeline.analysis_engine.active_tracks[1].face_info.matched_code == "USER_M"


@_with_patches
def test_self_identified_identity_still_requires_higher_score_to_switch(*_mocks):
    pipeline = _make_pipeline()
    pipeline.human_detector.detect_and_track.return_value = [(1, FRONT_BOX)]
    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detector_returning([(FRONT_FACE, 0.80)])
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.60, True, "NORMAL_USER")
    pipeline.process_frame(FRAME, current_time=0.0)

    pipeline.face_matcher.match_face.return_value = ("USER_B", "Tran Van B", 0.62, True, "NORMAL_USER")
    for t in (3.5, 7.0, 10.5):
        pipeline.process_frame(FRAME, current_time=t)
    assert pipeline.analysis_engine.active_tracks[1].face_info.matched_code == "USER_A"


def _identified(code, name, bbox):
    return FaceDetectionResult(
        bbox=bbox, score=0.92, matched_code=code, matched_name=name, is_authorized=True, access_checked=True
    )


def test_dedup_copy_from_coasting_track_is_marked_inherited_and_not_shared():
    engine = FrameAnalysisEngine()
    behind = BoundingBox(100, 100, 200, 300)
    front = BoundingBox(130, 100, 230, 300)  # IoM 0.7 > 0.55, IoU < 0.85
    engine.associate_face(track_id=1, face_result=_identified("USER_GA", "Phan Gia Anh", behind))
    engine.process_frame(detected_tracks=[(1, behind), (2, front)], camera_code="CAM-001", current_time=10.0)
    assert not engine.active_tracks[2].face_detected

    # Người phía sau bị che mất một frame -> track 1 còn "coasting", track 2 đang thấy
    engine.process_frame(detected_tracks=[(2, front)], camera_code="CAM-001", current_time=10.1)
    p2 = engine.active_tracks[2]
    p1 = engine.active_tracks[1]
    assert p2.face_info.matched_code == "USER_GA"
    assert p2.is_inherited_identity is True
    assert p2.face_info.is_inherited is True
    assert p2.face_info is not p1.face_info
    assert p1.face_info.is_inherited is False


def test_distance_only_parent_does_not_pass_identity_when_others_in_frame():
    engine = FrameAnalysisEngine()
    ga = BoundingBox(100, 100, 200, 300)
    other = BoundingBox(650, 100, 750, 300)
    engine.associate_face(track_id=1, face_result=_identified("USER_GA", "Phan Gia Anh", ga))
    engine.process_frame(detected_tracks=[(1, ga), (3, other)], camera_code="CAM-001", current_time=10.0)

    # Track 1 mất, track 2 mới xuất hiện cách 300px, không chồng lấn; track 3 vẫn trong frame
    newcomer = BoundingBox(400, 100, 500, 300)
    engine.process_frame(detected_tracks=[(2, newcomer), (3, other)], camera_code="CAM-001", current_time=10.2)
    p2 = engine.active_tracks[2]
    assert not (p2.face_info and p2.face_info.matched_code)
    assert p2.security_display_state != "AUTHORIZED"


def test_distance_only_parent_still_passes_identity_when_alone():
    engine = FrameAnalysisEngine()
    ga = BoundingBox(100, 100, 200, 300)
    engine.associate_face(track_id=1, face_result=_identified("USER_GA", "Phan Gia Anh", ga))
    engine.process_frame(detected_tracks=[(1, ga)], camera_code="CAM-001", current_time=10.0)

    newcomer = BoundingBox(400, 100, 500, 300)
    engine.process_frame(detected_tracks=[(2, newcomer)], camera_code="CAM-001", current_time=10.2)
    p2 = engine.active_tracks[2]
    assert p2.face_info is not None and p2.face_info.matched_code == "USER_GA"
    assert p2.is_inherited_identity is True


def test_parent_identity_is_safe_rules():
    parent = MagicMock()
    parent.bbox = BoundingBox(100, 100, 200, 300)
    parent.face_info = _identified("USER_GA", "Phan Gia Anh", parent.bbox)
    far = BoundingBox(400, 100, 500, 300)
    near = BoundingBox(110, 100, 210, 300)
    assert FrameAnalysisEngine.parent_identity_is_safe(near, parent, visible_count=3) is True
    assert FrameAnalysisEngine.parent_identity_is_safe(far, parent, visible_count=1) is True
    assert FrameAnalysisEngine.parent_identity_is_safe(far, parent, visible_count=2) is False
    assert FrameAnalysisEngine.parent_identity_is_safe(far, parent, visible_count=2, user_code="USER_GA") is True


class TestDetectorFaceFilter(unittest.TestCase):
    def setUp(self):
        self.detector = FaceDetector(upscale_threshold=160, max_dim=480)

    def _faces(self):
        return [
            FaceDetectionResult(bbox=BoundingBox(10.0, 10.0, 50.0, 50.0, confidence=0.95), score=0.95),
            FaceDetectionResult(bbox=BoundingBox(100.0, 100.0, 140.0, 140.0, confidence=0.70), score=0.70),
        ]

    def test_filter_skips_best_face_without_resize(self):
        crop = np.zeros((300, 200, 3), dtype=np.uint8)
        with patch.object(self.detector, "detect_in_image", return_value=self._faces()):
            res = self.detector.detect_best_face_in_crop(
                crop, offset_xy=(0, 0), face_filter=lambda f: f.bbox.x1 > 60
            )
        self.assertAlmostEqual(res.score, 0.70)

    def test_filter_receives_frame_coordinates_after_resize(self):
        crop = np.zeros((960, 720, 3), dtype=np.uint8)  # scale 0.5
        seen = []

        def _flt(f):
            seen.append(f.bbox.x1)
            return f.bbox.x1 > 1100  # chỉ mặt thứ 2: 100/0.5 + 1000 = 1200
        with patch.object(self.detector, "detect_in_image", return_value=self._faces()):
            res = self.detector.detect_best_face_in_crop(crop, offset_xy=(1000, 200), face_filter=_flt)
        self.assertAlmostEqual(seen[0], 1020.0, places=3)
        self.assertAlmostEqual(res.bbox.x1, 1200.0, places=3)
        self.assertAlmostEqual(res.bbox.y1, 400.0, places=3)

    def test_filter_rejecting_all_returns_none(self):
        crop = np.zeros((960, 720, 3), dtype=np.uint8)
        with patch.object(self.detector, "detect_in_image", return_value=self._faces()):
            res = self.detector.detect_best_face_in_crop(crop, offset_xy=(0, 0), face_filter=lambda f: False)
        self.assertIsNone(res)
