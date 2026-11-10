import time
from datetime import datetime, timezone, timedelta
import pytest
from src.core.entity import TrackedPerson, FaceDetectionResult, BoundingBox
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.config import settings

def test_quality_filter_does_not_overwrite_recognized_identity():
    """
    Lỗ hổng 1: Khi track đã nhận diện được danh tính (matched_code="CB001"),
    nếu các frame tiếp theo bị mờ hoặc crop nhỏ, associate_face không được
    ghi đè danh tính đã nhận diện thành rỗng.
    """
    engine = FrameAnalysisEngine()
    bbox = BoundingBox(100, 100, 200, 200)
    
    # Track 1 được nhận diện thành công
    valid_face = FaceDetectionResult(
        bbox=bbox,
        score=0.92,
        matched_code="CB001",
        matched_name="Can Bo A",
        is_authorized=True,
        access_checked=True
    )
    engine.associate_face(1, valid_face)
    engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=10.0)

    assert 1 in engine.active_tracks
    assert engine.active_tracks[1].face_info.matched_code == "CB001"

    # Mô phỏng face_result mới không nhận diện được danh tính (mờ / trượt)
    empty_face = FaceDetectionResult(
        bbox=bbox,
        score=0.0,
        matched_code=None,
        matched_name=None,
        is_authorized=False,
        access_checked=False
    )

    # Gọi associate_face
    engine.associate_face(1, empty_face)

    # Xác minh: Danh tính cũ CB001 vẫn được bảo toàn nguyên vẹn
    assert engine.active_tracks[1].face_info.matched_code == "CB001"
    assert engine.active_tracks[1].face_info.matched_name == "Can Bo A"
    assert engine.active_tracks[1].face_info.is_authorized is True


def test_track_inheritance_marks_inherited_and_forces_reverify():
    """
    Lỗ hổng 2: Khi Track mới kế thừa danh tính từ Track cũ do trùng vị trí (IoU > 0.35),
    Track mới phải được đánh dấu cờ is_inherited_identity = True để ép buộc
    pipeline quét mặt kiểm tra lại độc lập, triệt tiêu nguy cơ mượn danh.
    """
    engine = FrameAnalysisEngine()
    bbox1 = BoundingBox(100, 100, 200, 300)
    bbox2 = BoundingBox(102, 100, 202, 300)

    # Frame 1: Track 10 xuất hiện với danh tính hợp lệ
    face_info = FaceDetectionResult(
        bbox=bbox1,
        score=0.95,
        matched_code="GUARD01",
        matched_name="Bao Ve",
        is_authorized=True,
        access_checked=True
    )
    engine.associate_face(track_id=10, face_result=face_info)
    engine.process_frame([(10, bbox1)], camera_code="CAM-001", current_time=10.0)

    # Frame 2: Track 10 mất, Track 20 xuất hiện đè lên vị trí
    engine.process_frame([(20, bbox2)], camera_code="CAM-001", current_time=10.2)

    # Track 20 phải kế thừa và được đánh dấu is_inherited_identity = True
    assert 20 in engine.active_tracks
    p20 = engine.active_tracks[20]
    assert p20.is_inherited_identity is True
    assert p20.face_info is not None
    assert p20.face_info.matched_code == "GUARD01"
    assert getattr(p20.face_info, "is_inherited", False) is True


def test_access_grace_period_configurable_and_functional():
    """
    Lỗ hổng 3: Cấu hình access_grace_period (1.8s) bảo vệ người đã nhận diện
    danh tính nhưng đang chờ Backend HTTP phản hồi không bị bắn cảnh báo vi phạm trong 1.8s đầu tiên.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00", access_grace_period=1.8)
    assert engine.access_grace_period == 1.8

    t0 = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()
    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=False,
        access_checked=False
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Frame 1 tại t0
    _, alerts_f1, _ = engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=t0)
    assert len(alerts_f1) == 0

    # Tại t = t0 + 1.6s (< 1.8s): Đang trong grace period -> Vẫn KHÔNG bắn alert
    _, alerts_f2, _ = engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=t0 + 1.6)
    assert len(alerts_f2) == 0

    # Tại t = t0 + 1.9s (> 1.8s): Grace period hết hạn -> Phải bắn alert vi phạm
    _, alerts_f3, _ = engine.process_frame([(1, bbox)], camera_code="CAM-001", current_time=t0 + 1.9)
    assert len(alerts_f3) == 1
    assert alerts_f3[0].event_type == "UNAUTHORIZED_PERSON"
