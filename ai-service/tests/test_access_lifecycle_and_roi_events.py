"""
Unit tests for G4.1, G4.2, and G4.3:
- G4.1: Cache HIT on access check must NOT mark roi_entry_logged = True prematurely,
        allowing frame_analysis_engine to generate ENTRY roi_access_events for Backend.
- G4.2: Identity switch (A -> B) does NOT send redundant EXIT(A) call (since Backend dropped EXIT logging).
- G4.3: process_single_frame (/analyze-frame) does NOT query Backend for "UNKNOWN", avoiding redundant HTTP calls.
"""
from unittest.mock import patch, MagicMock
import numpy as np
import pytest

from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.pipeline.video_pipeline import VideoPipeline

PATCHES = (
    "src.pipeline.video_pipeline.HumanDetector",
    "src.pipeline.video_pipeline.FaceDetector",
    "src.pipeline.video_pipeline.FaceMatcher",
    "src.pipeline.video_pipeline.StorageService",
    "src.pipeline.video_pipeline.get_security_mqtt_client",
)


def _with_patches(test):
    for target in PATCHES:
        test = patch(target)(test)
    return test


def _make_pipeline():
    pipeline = VideoPipeline(camera_code="CAM-G4-TEST", enable_motion_gating=False)
    # ROI ở nửa dưới khung hình: [0, 300] -> [800, 600]
    pipeline.roi_polygon = [Point(0, 300), Point(800, 300), Point(800, 600), Point(0, 600)]
    pipeline.set_roi_polygon(pipeline.roi_polygon)

    m = pipeline.face_matcher
    m.get_cached_access.return_value = None
    m.get_camera_area_name.return_value = "Khu vực kiểm thử G4"
    m.is_camera_public.return_value = False
    m.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực kiểm thử G4", "ACCESS_LEVEL", None)
    return pipeline


FRAME = np.full((600, 800, 3), 120, dtype=np.uint8)


@_with_patches
def test_g4_1_cache_hit_does_not_suppress_roi_entry_event(*_mocks):
    """
    G4.1: Khi người được nhận diện ngoài ROI hoặc khi Cache HIT,
    hệ thống không được đánh dấu trước `roi_entry_logged = True`.
    Khi người đó bước chân vào ROI, engine phải tạo ra sự kiện `ENTRY` trong `roi_access_events`.
    """
    pipeline = _make_pipeline()
    # Giả lập cached access cho "USER_A"
    pipeline.face_matcher.get_cached_access.return_value = (
        True, "Được phép vào khu vực", "Khu vực kiểm thử G4", "STUDENT", None
    )

    # 1. Tạo Track 1 ở ngoài ROI (nửa trên khung hình: y: 50..200)
    outside_box = BoundingBox(100.0, 50.0, 200.0, 200.0) # bottom_center y = 200 < 300
    pipeline.human_detector.detect_and_track.return_value = [(1, outside_box)]
    pipeline.face_detector.detect_best_face_in_crop.return_value = FaceDetectionResult(
        bbox=BoundingBox(120.0, 60.0, 180.0, 120.0), score=0.95
    )
    pipeline.face_matcher.match_face.return_value = ("USER_A", "Nguyen Van A", 0.90, True, "STUDENT")

    # Xử lý frame ở ngoài ROI
    pipeline.process_frame(FRAME, current_time=1.0)
    p = pipeline.analysis_engine.active_tracks.get(1)
    assert p is not None
    assert p.face_info.matched_code == "USER_A"
    # G4.1: Phải đảm bảo chưa bị đánh dấu roi_entry_logged = True
    assert getattr(p, "roi_entry_logged", False) is False, "roi_entry_logged không được bật khi người đang ngoài ROI!"

    # 2. Người bước vào trong ROI (nửa dưới khung hình: y: 350..550)
    inside_box = BoundingBox(100.0, 350.0, 200.0, 550.0) # bottom_center y = 550 > 300
    pipeline.human_detector.detect_and_track.return_value = [(1, inside_box)]
    pipeline.face_detector.detect_best_face_in_crop.return_value = None # Không cần quét lại mặt vì score=0.90

    # Chạy process_frame khi đã ở trong ROI
    _, _, roi_events = pipeline.analysis_engine.process_frame(
        detected_tracks=[(1, inside_box)],
        roi_polygons=pipeline.roi_polygons,
        frame_size=(800, 600),
        camera_code=pipeline.camera_code,
        current_time=2.0
    )

    # Đảm bảo sinh ra sự kiện ENTRY cho USER_A
    assert len(roi_events) == 1, f"Kỳ vọng 1 sự kiện ENTRY nhưng nhận: {roi_events}"
    assert roi_events[0]["user_code"] == "USER_A"
    assert roi_events[0]["direction"] == "ENTRY"


@_with_patches
def test_g4_2_identity_switch_does_not_call_exit(*_mocks):
    """
    G4.2: Khi đổi danh tính từ USER_A sang USER_B trong ROI,
    hệ thống chỉ gửi ENTRY(USER_B), KHÔNG gửi EXIT(USER_A) vì Backend đã bỏ chức năng ghi nhận EXIT.
    """
    pipeline = _make_pipeline()
    matcher = pipeline.face_matcher

    # Gọi trực tiếp _handle_identity_switch
    pipeline._handle_identity_switch(
        track_id=1,
        old_code="USER_A",
        new_code="USER_B",
        roi_label="Cổng chính",
        need_entry=True
    )

    # Kiểm tra các lệnh gọi tới check_camera_access
    calls = matcher.check_camera_access.call_args_list
    assert len(calls) == 1, f"Kỳ vọng đúng 1 cuộc gọi (ENTRY), nhưng nhận {len(calls)} calls: {calls}"

    _, kwargs = calls[0]
    # Cuộc gọi phải là ENTRY cho USER_B
    args = calls[0].args
    called_code = args[1] if len(args) > 1 else kwargs.get("user_code")
    direction = kwargs.get("event_direction")

    assert called_code == "USER_B", f"Kỳ vọng ghi ENTRY cho USER_B, nhận {called_code}"
    assert direction == "ENTRY", f"Kỳ vọng direction ENTRY, nhận {direction}"

    # Đảm bảo không có cuộc gọi nào mang EXIT
    for call in calls:
        assert call.kwargs.get("event_direction") != "EXIT", "Lỗi G4.2: Vẫn còn gọi check_camera_access với EXIT!"


@_with_patches
def test_g4_3_process_single_frame_unknown_does_not_call_backend(*_mocks):
    """
    G4.3: Trong process_single_frame (/analyze-frame), khi phát hiện khuôn mặt người lạ (không khớp trong CSDL),
    hệ thống tự gán trạng thái người lạ mà KHÔNG gọi HTTP tới Backend check_camera_access("UNKNOWN").
    """
    pipeline = _make_pipeline()
    matcher = pipeline.face_matcher
    matcher.check_camera_access.reset_mock()

    # Giả lập: Human detector tìm thấy 1 người
    boxes = [(1, BoundingBox(100.0, 100.0, 300.0, 500.0))]
    pipeline.human_detector.detect.return_value = boxes
    pipeline.human_detector.detect_and_track.return_value = boxes
    # Face detector tìm thấy mặt
    pipeline.face_detector.detect_best_face_in_crop.return_value = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0), score=0.90
    )
    # Face matcher KHÔNG khớp với ai trong CSDL (trả None)
    matcher.match_face.return_value = None

    annotated, persons, alerts = pipeline.process_single_frame(FRAME, current_time=1.0)

    # Kiểm tra rằng KHÔNG có cuộc gọi check_camera_access("UNKNOWN") nào tới Backend
    for call in matcher.check_camera_access.call_args_list:
        args = call.args
        kwargs = call.kwargs
        code = args[1] if len(args) > 1 else kwargs.get("user_code")
        assert code != "UNKNOWN", f"Lỗi G4.3: Không được gọi check_camera_access cho UNKNOWN! Nhận: {call}"

    # Kiểm tra person kết quả được đánh dấu chính xác là người lạ chưa xác thực
    assert len(persons) == 1
    p = persons[0]
    assert p.face_info is not None
    assert p.face_info.is_authorized is False
    assert p.face_info.access_reason == "Người lạ chưa xác thực"
    assert p.face_info.access_checked is True
