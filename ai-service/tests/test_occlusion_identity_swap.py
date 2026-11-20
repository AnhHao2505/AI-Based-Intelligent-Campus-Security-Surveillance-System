"""
Hai người đứng sát / che nhau trong ROI: người có quyền (Gia Anh) bị người không có quyền (Minh) che.
1) Một mã người chỉ được mang bởi một box đang thấy: Gia Anh hiện ra lại và được nhận diện bằng mặt trên track mới
   -> track cũ đang dính danh tính Gia Anh (thực ra là Minh) bị gỡ danh tính, xác thực lại từ đầu.
2) Hai người chồng lấn: danh tính đã nhận diện bị coi là chưa chắc -> quét lại mặt; ByteTrack tráo ID sang Minh
   thì mặt Minh không khớp Gia Anh -> gỡ danh tính (Minh chưa đăng ký) hoặc đổi sang Minh (Minh có đăng ký).
"""
from unittest.mock import patch

import numpy as np

from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline


def _identified(code, name, bbox, matched_at, score=0.90):
    return FaceDetectionResult(
        bbox=bbox, score=score, matched_code=code, matched_name=name,
        is_authorized=True, access_checked=True, matched_at=matched_at
    )


def _code(p):
    return p.face_info.matched_code if (p.face_detected and p.face_info) else None


GA_BOX = BoundingBox(100, 100, 200, 400)
MINH_BOX = BoundingBox(400, 100, 500, 400)


def test_fresh_face_match_takes_identity_from_other_visible_track():
    engine = FrameAnalysisEngine()
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=1.0))
    engine.process_frame(detected_tracks=[(1, GA_BOX)], camera_code="CAM-001", current_time=1.0)
    assert engine.active_tracks[1].security_display_state == "AUTHORIZED"

    # Track 1 đã bị ByteTrack đẩy sang Minh; Gia Anh hiện ra lại thành track 2 và được nhận diện bằng mặt
    engine.process_frame(detected_tracks=[(1, MINH_BOX)], camera_code="CAM-001", current_time=4.0)
    engine.associate_face(2, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=5.0))
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, MINH_BOX), (2, GA_BOX)], camera_code="CAM-001", current_time=5.0
    )

    assert _code(engine.active_tracks[2]) == "USER_GA"
    minh = engine.active_tracks[1]
    assert _code(minh) is None, "Không được có 2 box cùng mang danh tính Phan Gia Anh"
    assert minh.security_display_state == "VERIFYING"
    assert minh.roi_enter_time == 5.0, "Có lại khoảng chờ xác thực tính từ lúc bị gỡ danh tính"
    assert alerts == []


def test_released_track_is_alerted_as_unknown_after_new_grace_period():
    engine = FrameAnalysisEngine()
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=1.0))
    engine.process_frame(detected_tracks=[(1, GA_BOX)], camera_code="CAM-001", current_time=1.0)
    engine.associate_face(2, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=5.0))
    engine.process_frame(detected_tracks=[(1, MINH_BOX), (2, GA_BOX)], camera_code="CAM-001", current_time=5.0)

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, MINH_BOX), (2, GA_BOX)], camera_code="CAM-001", current_time=5.0 + engine.access_grace_period + 0.1
    )
    assert [(a.event_type, a.track_id) for a in alerts] == [("UNKNOWN_PERSON", 1)]


def test_inherited_identity_loses_to_direct_face_match():
    engine = FrameAnalysisEngine()
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=1.0))
    engine.process_frame(detected_tracks=[(1, GA_BOX)], camera_code="CAM-001", current_time=1.0)
    engine.process_frame(detected_tracks=[(1, GA_BOX), (3, MINH_BOX)], camera_code="CAM-001", current_time=1.1)
    minh = engine.active_tracks[3]
    FrameAnalysisEngine._copy_identity_as_inherited(minh, engine.active_tracks[1])
    assert minh.identity_verified_at is None

    # Track 1 của Gia Anh vừa xác nhận lại mặt -> track kế thừa (Minh) phải nhả danh tính
    engine.active_tracks[1].identity_verified_at = 2.0
    engine.process_frame(detected_tracks=[(1, GA_BOX), (3, MINH_BOX)], camera_code="CAM-001", current_time=2.0)
    assert _code(engine.active_tracks[1]) == "USER_GA"
    assert _code(engine.active_tracks[3]) is None


def test_same_frame_claims_keep_higher_score():
    engine = FrameAnalysisEngine()
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, MINH_BOX)], camera_code="CAM-001", current_time=1.0)
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=2.0, score=0.91))
    engine.associate_face(2, _identified("USER_GA", "Phan Gia Anh", MINH_BOX, matched_at=2.0, score=0.62))
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, MINH_BOX)], camera_code="CAM-001", current_time=2.0)
    assert _code(engine.active_tracks[1]) == "USER_GA"
    assert _code(engine.active_tracks[2]) is None


def test_double_box_of_same_person_is_not_released():
    engine = FrameAnalysisEngine()
    twin = BoundingBox(101, 100, 201, 400)
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, twin)], camera_code="CAM-001", current_time=1.0)
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=2.0))
    engine.associate_face(2, _identified("USER_GA", "Phan Gia Anh", twin, matched_at=1.5))
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, twin)], camera_code="CAM-001", current_time=2.0)
    # Box kép bị khối khử trùng lặp ẩn đi (track 2 không được tạo); khối gỡ danh tính không được xoá mặt của nó
    assert 2 not in engine.active_tracks
    assert engine.pending_faces[2].matched_code == "USER_GA"
    assert _code(engine.active_tracks[1]) == "USER_GA"


def test_identity_held_only_by_inherited_tracks_is_left_to_face_reverify():
    engine = FrameAnalysisEngine()
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, MINH_BOX)], camera_code="CAM-001", current_time=1.0)
    donor = _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=0.5)
    for tid in (1, 2):
        p = engine.active_tracks[tid]
        p.face_detected, p.face_info, p.is_inherited_identity = True, donor, True
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, MINH_BOX)], camera_code="CAM-001", current_time=2.0)
    assert _code(engine.active_tracks[1]) == "USER_GA"
    assert _code(engine.active_tracks[2]) == "USER_GA"


def test_overlapping_people_mark_identity_for_reverify():
    engine = FrameAnalysisEngine()
    engine.associate_face(1, _identified("USER_GA", "Phan Gia Anh", GA_BOX, matched_at=1.0))
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, MINH_BOX)], camera_code="CAM-001", current_time=1.0)
    assert engine.active_tracks[1].is_inherited_identity is False, "Đứng xa nhau: không quét lại"

    minh_close = BoundingBox(150, 120, 250, 400)
    engine.process_frame(detected_tracks=[(1, GA_BOX), (2, minh_close)], camera_code="CAM-001", current_time=1.2)
    ga = engine.active_tracks[1]
    assert ga.is_inherited_identity is True
    assert _code(ga) == "USER_GA", "Chỉ đánh dấu quét lại, chưa gỡ danh tính"
    assert ga.security_display_state == "AUTHORIZED"


# ---------------------------------------------------------------- pipeline (SCRFD / ArcFace giả lập)

PATCHES = (
    "src.pipeline.video_pipeline.HumanDetector",
    "src.pipeline.video_pipeline.FaceDetector",
    "src.pipeline.video_pipeline.FaceMatcher",
    "src.pipeline.video_pipeline.StorageService",
    "src.pipeline.video_pipeline.get_security_mqtt_client",
)

FRAME = np.full((600, 800, 3), 120, dtype=np.uint8)
P_GA_BOX = BoundingBox(100.0, 100.0, 300.0, 550.0)
P_MINH_BOX = BoundingBox(180.0, 110.0, 380.0, 550.0)  # đứng sát, chồng lấn Gia Anh
GA_FACE = BoundingBox(170.0, 115.0, 230.0, 185.0)
MINH_FACE = BoundingBox(250.0, 125.0, 310.0, 195.0)


def _with_patches(test):
    for target in PATCHES:
        test = patch(target)(test)
    return test


def _make_pipeline(minh_registered: bool):
    pipeline = VideoPipeline(camera_code="CAM-OCCLUSION-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    m = pipeline.face_matcher
    m.get_cached_access.return_value = None
    m.get_camera_area_name.return_value = "Khu vực test"
    m.is_camera_public.return_value = False
    m.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_LEVEL")
    state = {"faces": [], "last": None}

    def _detect(crop, offset_xy=(0, 0), face_filter=None):
        ox, oy = offset_xy
        h, w = crop.shape[:2]
        found = []
        for fb in state["faces"]:
            cx, cy = (fb.x1 + fb.x2) / 2.0, (fb.y1 + fb.y2) / 2.0
            if ox <= cx <= ox + w and oy <= cy <= oy + h:
                found.append(FaceDetectionResult(bbox=fb, score=0.95))
        if face_filter is not None:
            found = [f for f in found if face_filter(f)]
        state["last"] = found[0].bbox if found else None
        return found[0] if found else None

    def _match(*_a, **_k):
        if state["last"] == GA_FACE:
            return ("USER_GA", "Phan Gia Anh", 0.90, True, "NORMAL_USER")
        if state["last"] == MINH_FACE and minh_registered:
            return ("USER_MINH", "Nguyen Hoang Minh", 0.70, True, "NORMAL_USER")
        m.last_best_score = 0.08
        return None

    pipeline.face_detector.detect_best_face_in_crop.side_effect = _detect
    m.match_face.side_effect = _match
    return pipeline, state


def _run(pipeline, state, t, tracks, faces):
    pipeline.human_detector.detect_and_track.return_value = tracks
    state["faces"] = faces
    pipeline.process_frame(FRAME, current_time=t)


@_with_patches
def test_bytetrack_swap_onto_unregistered_person_is_revoked(*_mocks):
    pipeline, state = _make_pipeline(minh_registered=False)
    _run(pipeline, state, 0.0, [(1, P_GA_BOX)], [GA_FACE])
    assert _code(pipeline.analysis_engine.active_tracks[1]) == "USER_GA"

    # Minh bước tới đứng sát Gia Anh (mặt Minh cúi, chưa thấy)
    _run(pipeline, state, 0.3, [(1, P_GA_BOX), (2, P_MINH_BOX)], [GA_FACE])
    assert pipeline.analysis_engine.active_tracks[1].is_inherited_identity is True

    # Gia Anh bị che hẳn, ByteTrack đẩy track 1 sang Minh; Minh ngẩng mặt lên
    for t in (0.6, 0.9, 1.2):
        _run(pipeline, state, t, [(1, P_MINH_BOX)], [MINH_FACE])
    assert _code(pipeline.analysis_engine.active_tracks[1]) is None, "Mặt Minh rõ là người khác -> gỡ danh tính Gia Anh"


@_with_patches
def test_bytetrack_swap_onto_registered_person_switches_identity(*_mocks):
    pipeline, state = _make_pipeline(minh_registered=True)
    _run(pipeline, state, 0.0, [(1, P_GA_BOX)], [GA_FACE])
    _run(pipeline, state, 0.3, [(1, P_GA_BOX), (2, P_MINH_BOX)], [GA_FACE])
    for t in (0.6, 0.9, 1.2):
        _run(pipeline, state, t, [(1, P_MINH_BOX)], [MINH_FACE])
    assert _code(pipeline.analysis_engine.active_tracks[1]) == "USER_MINH"


@_with_patches
def test_person_reappearing_from_behind_leaves_single_identity_box(*_mocks):
    pipeline, state = _make_pipeline(minh_registered=False)
    _run(pipeline, state, 0.0, [(1, P_GA_BOX)], [GA_FACE])
    # Track 1 bị đẩy sang Minh, Minh cúi mặt (không thấy mặt nên không quét lại được)
    for t in (0.3, 0.6):
        _run(pipeline, state, t, [(1, P_MINH_BOX)], [])
    assert _code(pipeline.analysis_engine.active_tracks[1]) == "USER_GA"

    # Gia Anh hiện ra lại thành track mới, nhận diện được mặt
    for t in (0.9, 1.2):
        _run(pipeline, state, t, [(1, P_MINH_BOX), (5, P_GA_BOX)], [GA_FACE])
    tracks = pipeline.analysis_engine.active_tracks
    holders = [tid for tid, p in tracks.items() if _code(p) == "USER_GA" and getattr(p, "is_currently_visible", True)]
    assert holders == [5], f"Chỉ được 1 box mang danh tính Gia Anh, đang có: {holders}"
    assert tracks[1].security_display_state == "VERIFYING"
