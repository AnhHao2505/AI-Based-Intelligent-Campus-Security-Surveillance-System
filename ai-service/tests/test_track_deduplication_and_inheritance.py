import pytest
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, Point, FaceDetectionResult


def test_characterization_valid_id_switch_inherits_identity():
    """
    Scenario 1: Valid ByteTrack ID switch (cùng một người).
    Track 1 xuất hiện và nhận diện khuôn mặt.
    Ở frame tiếp theo, ByteTrack mất Track 1 và tạo Track 2 tại vị trí trùng lặp cao (IoU > 0.6).
    Track 2 PHẢI kế thừa danh tính từ Track 1.
    """
    engine = FrameAnalysisEngine()
    bbox1 = BoundingBox(100, 100, 200, 300)
    bbox2 = BoundingBox(102, 100, 202, 300)

    # Frame 1: Track 1 xuất hiện với danh tính hợp lệ
    face_info = FaceDetectionResult(
        bbox=bbox1,
        score=0.92,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True
    )
    engine.associate_face(track_id=1, face_result=face_info)
    engine.process_frame(detected_tracks=[(1, bbox1)], camera_code="CAM-001", current_time=10.0)

    assert 1 in engine.active_tracks
    assert engine.active_tracks[1].face_info.matched_code == "SE194249"

    # Frame 2: Track 1 biến mất, Track 2 xuất hiện tại cùng vị trí (ID switch)
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(2, bbox2)],
        camera_code="CAM-001",
        current_time=10.2
    )

    # Track 2 phải kế thừa danh tính từ Track 1
    assert 2 in engine.active_tracks
    assert engine.active_tracks[2].face_detected is True
    assert engine.active_tracks[2].face_info is not None
    assert engine.active_tracks[2].face_info.matched_code == "SE194249"
    # Track 1 cũ bị dọn dẹp hoặc đánh dấu không còn visible
    assert 1 not in engine.active_tracks or not engine.active_tracks[1].is_currently_visible


def test_characterization_two_close_people_stranger_does_not_steal_identity():
    """
    Scenario 2: Hai người đứng sát nhau trong cùng frame (Kẻ lạ cạnh người hợp lệ).
    Track 1 là người hợp lệ (đã nhận diện).
    Track 2 là người lạ đứng cạnh Track 1, có Bounding Box chồng lấn (IoU > 0.35 hoặc IoM > 0.55).
    YÊU CẦU:
    - Track 1 của người hợp lệ KHÔNG BỊ XÓA.
    - Track 2 (người lạ) KHÔNG ĐƯỢC kế thừa danh tính của Track 1.
    - Track 2 vẫn là người chưa xác định (face_detected=False hoặc matched_code=None).
    """
    engine = FrameAnalysisEngine()
    # bbox1 và bbox2 chồng lấn nhau (overlap)
    bbox1 = BoundingBox(100, 100, 200, 300)
    bbox2 = BoundingBox(130, 100, 230, 300)  # IoU > 0.40

    # Frame 1: Chỉ có Track 1
    face_info = FaceDetectionResult(
        bbox=bbox1,
        score=0.92,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True
    )
    engine.associate_face(track_id=1, face_result=face_info)
    engine.process_frame(detected_tracks=[(1, bbox1)], camera_code="CAM-001", current_time=10.0)

    assert 1 in engine.active_tracks
    assert engine.active_tracks[1].face_info.matched_code == "SE194249"

    # Frame 2: CẢ HAI NGƯỜI CÙNG XUẤT HIỆN trong frame!
    tracks, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=10.2
    )

    # 1. Track 1 của người hợp lệ KHÔNG ĐƯỢC BỊ XÓA khỏi active_tracks
    assert 1 in engine.active_tracks, "Track 1 của người hợp lệ không được bị xóa khi có người lạ đứng gần!"
    assert engine.active_tracks[1].is_currently_visible is True, "Track 1 phải tiếp tục visible!"
    assert engine.active_tracks[1].face_info.matched_code == "SE194249"

    # 2. Track 2 của người lạ KHÔNG ĐƯỢC đánh cắp danh tính của Track 1
    assert 2 in engine.active_tracks, "Track 2 phải tồn tại trong active_tracks"
    p2 = engine.active_tracks[2]
    p2_code = p2.face_info.matched_code if (p2.face_detected and p2.face_info) else None
    assert p2_code != "SE194249", "Kẻ lạ Track 2 KHÔNG ĐƯỢC kế thừa danh tính SE194249 của người hợp lệ!"


def test_characterization_active_dedup_does_not_suppress_active_concurrent_tracks():
    """
    Scenario 3: Hai người cùng phát hiện trong frame, đứng gần nhau.
    Khối khử trùng lặp cuối hàm không được đánh cắp face_info giữa 2 track đang cùng xuất hiện,
    và không được ẩn đi 1 trong 2 người đang hiện diện.
    """
    engine = FrameAnalysisEngine()
    bbox1 = BoundingBox(100, 100, 200, 300)
    bbox2 = BoundingBox(130, 100, 230, 300)

    face_info = FaceDetectionResult(
        bbox=bbox1,
        score=0.92,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Frame 1: Cả hai track 1 và 2 cùng được phát hiện
    engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=10.0
    )

    p1 = engine.active_tracks[1]
    p2 = engine.active_tracks[2]

    # Track 1 hợp lệ
    assert p1.is_currently_visible is True
    assert p1.face_info.matched_code == "SE194249"

    # Track 2 là stranger, không được bị đánh cắp face
    p2_code = p2.face_info.matched_code if (p2.face_detected and p2.face_info) else None
    assert p2_code is None, "Track 2 không được mang face_info của Track 1"
def test_detector_double_box_single_person_unknown_alerts():
    """
    Điểm 3: Một người bị detector sinh box kép (IoU 0.5 - 0.85).
    Kiểm tra số cảnh báo UNKNOWN phát ra.
    Chỉ được phát tối đa 1 cảnh báo duy nhất cho người này.
    """
    engine = FrameAnalysisEngine()
    # bbox1 và bbox2 sinh ra cho cùng 1 người với IoU ~ 0.71 (trong khoảng 0.5 - 0.85)
    bbox1 = BoundingBox(100, 100, 200, 300, confidence=0.90)
    bbox2 = BoundingBox(115, 105, 215, 305, confidence=0.75)
    assert 0.50 <= bbox1.iou(bbox2) <= 0.85

    total_alerts = []
    # Frame 1: t=0.0
    _, a1, _ = engine.process_frame([(1, bbox1), (2, bbox2)], camera_code="CAM-001", current_time=0.0)
    total_alerts.extend(a1)

    # Frame 2: t=2.0 (> 1.5s grace)
    _, a2, _ = engine.process_frame([(1, bbox1), (2, bbox2)], camera_code="CAM-001", current_time=2.0)
    total_alerts.extend(a2)

    # Frame 3: t=6.0 (> 3.0s camera min interval)
    _, a3, _ = engine.process_frame([(1, bbox1), (2, bbox2)], camera_code="CAM-001", current_time=6.0)
    total_alerts.extend(a3)

    # Chỉ được có đúng 1 cảnh báo UNKNOWN cho cùng 1 cá nhân này
    assert len(total_alerts) == 1, f"Expected 1 alert for double box of 1 person, got {len(total_alerts)}"


def test_two_real_persons_close_together_not_merged_into_one_track():
    """
    Điểm 3: Hai người THẬT đứng sát nhau (IoU 0.50 - 0.60, tâm tách biệt).
    Đảm bảo hai người không bị gộp thành 1 track hoặc một người bị coi là box kép.
    Cả hai track đều phải duy trì hiển thị và hoạt động độc lập.
    """
    engine = FrameAnalysisEngine()
    # bbox1 và bbox2 của 2 người thật đứng sát nhau: w=120, h=300, IoU = 0.60, tâm x lệch 30px (25% chiều rộng)
    bbox1 = BoundingBox(100, 100, 220, 400, confidence=0.88)
    bbox2 = BoundingBox(130, 100, 250, 400, confidence=0.85)

    iou = bbox1.iou(bbox2)
    assert 0.50 <= iou <= 0.60, f"IoU should be in [0.50, 0.60], got {iou}"
    # Tâm tách biệt
    dx = abs(bbox1.center.x - bbox2.center.x)
    assert dx >= 0.20 * min(bbox1.width, bbox2.width), "Center must be separated"

    # Frame 1: cả 2 người cùng vào khung hình
    active_persons, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox1), (2, bbox2)],
        camera_code="CAM-001",
        current_time=0.0
    )

    visible_tids = {p.track_id for p in active_persons if p.is_currently_visible}
    assert 1 in visible_tids, "Track 1 phải hiển thị"
    assert 2 in visible_tids, "Track 2 phải hiển thị (không bị coi là double box glitch của Track 1)"
    assert len(visible_tids) == 2, f"Cả 2 người thật phải có 2 track riêng biệt, got {visible_tids}"
