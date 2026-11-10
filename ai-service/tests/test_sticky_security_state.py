import time
import numpy as np
import pytest
from src.core.entity import TrackedPerson, BoundingBox, FaceDetectionResult, RoiPolygonConfig, Point
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.utils.visualizer import FrameVisualizer


def test_neutral_outside_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    # Outside ROI: pixel coords (50, 50, 150, 200), bottom_center is at (100, 200)
    bbox_outside = BoundingBox(50, 50, 150, 200, confidence=0.9)
    tracks = [(101, bbox_outside)]
    
    persons, alerts, events = engine.process_frame(
        detected_tracks=tracks,
        roi_polygons=[polygon],
        current_time=100.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    
    assert len(persons) == 1
    p = persons[0]
    assert not p.is_in_roi
    assert not p.has_entered_roi
    assert p.security_display_state == "NEUTRAL"
    assert len(alerts) == 0

    # Test visualizer renders Gray (180, 180, 180) on the bottom edge
    test_frame = np.zeros((1000, 1000, 3), dtype=np.uint8)
    rendered = FrameVisualizer.draw_tracked_persons(test_frame.copy(), persons)
    pixel = rendered[200, 100]
    assert tuple(pixel) == (180, 180, 180), f"Expected Gray (180, 180, 180), got {tuple(pixel)}"


def test_verifying_grace_period_in_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    # Inside ROI: pixel coords (450, 400, 550, 550), bottom_center is at (500, 550)
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    tracks = [(102, bbox_inside)]
    
    # t = 0.5s after first appearance (within 1.5s grace period)
    persons, alerts, events = engine.process_frame(
        detected_tracks=tracks,
        roi_polygons=[polygon],
        current_time=100.5,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    
    assert len(persons) == 1
    p = persons[0]
    assert p.is_in_roi
    assert p.has_entered_roi
    assert p.security_display_state == "VERIFYING"
    assert len(alerts) == 0  # No alert during grace period!

    # Test visualizer renders Yellow-Orange (0, 165, 255) on the bottom edge
    test_frame = np.zeros((1000, 1000, 3), dtype=np.uint8)
    rendered = FrameVisualizer.draw_tracked_persons(test_frame.copy(), persons)
    pixel = rendered[550, 500]
    assert tuple(pixel) == (0, 165, 255), f"Expected Yellow-Orange (0, 165, 255), got {tuple(pixel)}"


def test_unknown_after_grace_period_in_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    tracks = [(103, bbox_inside)]
    
    # First frame at t=100.0 (enters ROI)
    engine.process_frame(
        detected_tracks=tracks,
        roi_polygons=[polygon],
        current_time=100.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    
    # Next frame at t=102.0s (> 1.5s grace period expired)
    persons, alerts, events = engine.process_frame(
        detected_tracks=tracks,
        roi_polygons=[polygon],
        current_time=102.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    
    p = persons[0]
    assert p.is_in_roi
    assert p.security_display_state == "UNKNOWN"
    assert len(alerts) == 1
    assert alerts[0].event_type == "UNKNOWN_PERSON"

    # Test visualizer renders Red (0, 0, 255) on the bottom edge
    test_frame = np.zeros((1000, 1000, 3), dtype=np.uint8)
    rendered = FrameVisualizer.draw_tracked_persons(test_frame.copy(), persons)
    pixel = rendered[550, 500]
    assert tuple(pixel) == (0, 0, 255), f"Expected Red (0, 0, 255), got {tuple(pixel)}"


def test_unauthorized_sticky_state_after_exiting_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    
    # Associate face with UNAUTHORIZED user
    engine.associate_face(104, FaceDetectionResult(
        bbox=bbox_inside,
        score=0.95,
        matched_code="SE123456",
        matched_name="Le Van Unauthorized",
        is_authorized=False,
        access_checked=True,
        access_reason="Khong co quyen"
    ))
    
    # Frame 1: inside ROI
    persons, alerts, _ = engine.process_frame(
        detected_tracks=[(104, bbox_inside)],
        roi_polygons=[polygon],
        current_time=100.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    p = persons[0]
    assert p.is_in_roi
    assert p.security_display_state == "UNAUTHORIZED"

    # Frame 2: Person moves OUT of ROI into room (750, 700, 850, 850)
    bbox_in_room = BoundingBox(750, 700, 850, 850, confidence=0.9)
    persons_step2, _, _ = engine.process_frame(
        detected_tracks=[(104, bbox_in_room)],
        roi_polygons=[polygon],
        current_time=101.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    
    p2 = persons_step2[0]
    assert not p2.is_in_roi, "Person should be outside ROI"
    assert p2.has_entered_roi, "Person should have entered ROI previously"
    # CRITICAL TEST: Must stay UNAUTHORIZED (Red) even outside ROI!
    assert p2.security_display_state == "UNAUTHORIZED", f"Expected UNAUTHORIZED, got {p2.security_display_state}"

    # Verify visualizer STILL renders Red (0, 0, 255) outside ROI on bottom edge
    test_frame = np.zeros((1000, 1000, 3), dtype=np.uint8)
    rendered = FrameVisualizer.draw_tracked_persons(test_frame.copy(), persons_step2)
    pixel = rendered[850, 800]
    assert tuple(pixel) == (0, 0, 255), f"Expected Red (0, 0, 255) outside ROI, got {tuple(pixel)}"


def test_authorized_sticky_state_after_exiting_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    
    # Associate face with AUTHORIZED user
    engine.associate_face(105, FaceDetectionResult(
        bbox=bbox_inside,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True,
        access_reason="Hop le"
    ))
    
    # Frame 1: inside ROI
    persons, _, _ = engine.process_frame(
        detected_tracks=[(105, bbox_inside)],
        roi_polygons=[polygon],
        current_time=100.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    assert persons[0].security_display_state == "AUTHORIZED"

    # Frame 2: Person moves OUT of ROI into room
    bbox_in_room = BoundingBox(750, 700, 850, 850, confidence=0.9)
    persons_step2, _, _ = engine.process_frame(
        detected_tracks=[(105, bbox_in_room)],
        roi_polygons=[polygon],
        current_time=101.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    p2 = persons_step2[0]
    assert not p2.is_in_roi
    assert p2.security_display_state == "AUTHORIZED"

    # Verify visualizer STILL renders Green (0, 255, 0) outside ROI on bottom edge
    test_frame = np.zeros((1000, 1000, 3), dtype=np.uint8)
    rendered = FrameVisualizer.draw_tracked_persons(test_frame.copy(), persons_step2)
    pixel = rendered[850, 800]
    assert tuple(pixel) == (0, 255, 0), f"Expected Green (0, 255, 0) outside ROI, got {tuple(pixel)}"


def test_late_identity_upgrade_outside_roi():
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    
    # Frame 1: enters ROI with no face, stays > 1.5s -> UNKNOWN
    engine.process_frame([(106, bbox_inside)], roi_polygons=[polygon], current_time=100.0, camera_code="CAM-002", frame_size=(1000, 1000))
    persons, _, _ = engine.process_frame([(106, bbox_inside)], roi_polygons=[polygon], current_time=102.0, camera_code="CAM-002", frame_size=(1000, 1000))
    assert persons[0].security_display_state == "UNKNOWN"

    # Frame 2: Moves into room (outside ROI)
    bbox_in_room = BoundingBox(750, 700, 850, 850, confidence=0.9)
    # Face is now recognized as AUTHORIZED
    engine.associate_face(106, FaceDetectionResult(
        bbox=bbox_in_room,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True
    ))
    
    persons_step2, _, _ = engine.process_frame(
        detected_tracks=[(106, bbox_in_room)],
        roi_polygons=[polygon],
        current_time=103.0,
        camera_code="CAM-002",
        frame_size=(1000, 1000)
    )
    p2 = persons_step2[0]
    # Successfully upgraded to AUTHORIZED!
    assert p2.security_display_state == "AUTHORIZED"
    assert not p2.alert_unknown_sent


@pytest.mark.requires_model
def test_pipeline_sticky_state_with_track_switch():
    from src.pipeline.video_pipeline import VideoPipeline

    pipeline = VideoPipeline(camera_code="CAM-002", analysis_max_fps=15.0)
    pipeline.analysis_engine.after_hour_start = "23:59"
    pipeline.analysis_engine.after_hour_end = "23:59"
    roi_poly = [{"x": 0.50, "y": 0.70}, {"x": 0.70, "y": 0.70}, {"x": 0.70, "y": 0.90}, {"x": 0.50, "y": 0.90}]
    pipeline.set_roi_config([{"label": "Vung giam sat", "vertices": roi_poly, "alert_rules": ["ENTRY_EXIT_TRACKING"]}])

    # Mock backend access check
    pipeline.face_matcher.check_camera_access = lambda cam, user, **kw: (False, "Khong co quyen truy cap", "Lab 1", None)
    pipeline.face_matcher.get_cached_access = lambda cam, user: (False, "Khong co quyen truy cap", "Lab 1", None)

    w, h = 2688, 1664
    frame = np.zeros((h, w, 3), dtype=np.uint8)

    # Frame 1: Person enters ROI, unauthorized
    bbox_in_roi = BoundingBox(1400, 800, 1600, 1331)
    pipeline.human_detector.detect_and_track = lambda f, persist=True: [(54, bbox_in_roi)]
    face_res = FaceDetectionResult(bbox=BoundingBox(1450, 820, 1550, 950), score=0.95)
    pipeline.face_detector.detect_best_face_in_crop = lambda crop, offset_xy=(0,0): face_res
    pipeline.face_matcher.match_face = lambda crop, **kw: ("SE194249", "Tran Hoang Nam", 0.92, True, "STUDENT")

    res_frame1, persons1, alerts1 = pipeline.process_frame(frame, need_annotated=True)
    p1 = pipeline.analysis_engine.active_tracks[54]
    assert p1.security_display_state == "UNAUTHORIZED"

    # Frame 2: Person steps OUT of ROI into room (same track 54)
    bbox_out_roi = BoundingBox(1400, 1000, 1600, 1580)
    pipeline.human_detector.detect_and_track = lambda f, persist=True: [(54, bbox_out_roi)]
    res_frame2, persons2, alerts2 = pipeline.process_frame(frame, need_annotated=True)
    p2 = pipeline.analysis_engine.active_tracks[54]
    assert p2.security_display_state == "UNAUTHORIZED"

    # Frame 3: ByteTrack switches to track 55 outside ROI
    bbox_track_55 = BoundingBox(1420, 1050, 1620, 1600)
    pipeline.human_detector.detect_and_track = lambda f, persist=True: [(55, bbox_track_55)]
    res_frame3, persons3, alerts3 = pipeline.process_frame(frame, need_annotated=True)
    p3 = pipeline.analysis_engine.active_tracks.get(55)
    assert p3 is not None
    # Must remain UNAUTHORIZED (Red) across track ID switch!
    assert p3.security_display_state == "UNAUTHORIZED"



def test_walking_outside_roi_preserves_full_grace_period_upon_entry():
    """
    Kiểm tra lỗi Grace Period: Người đi bộ ngoài hành lang 3.0s rồi mới bước vào ROI.
    Hệ thống KHÔNG ĐƯỢC PHÉP báo đỏ ngay frame đầu tiên, mà phải cấp trọn vẹn 2.0s VERIFYING!
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, access_grace_period=2.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_outside = BoundingBox(50, 50, 150, 200, confidence=0.9)
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)

    # Đi ngoài ROI từ t = 100.0s đến t = 103.0s (đã xuất hiện 3 giây)
    engine.process_frame([(201, bbox_outside)], roi_polygons=[polygon], current_time=100.0, frame_size=(1000, 1000))
    persons_out, alerts_out, _ = engine.process_frame([(201, bbox_outside)], roi_polygons=[polygon], current_time=103.0, frame_size=(1000, 1000))
    assert persons_out[0].security_display_state == "NEUTRAL"
    assert len(alerts_out) == 0

    # Chạm chân vào ROI tại t = 103.0s -> Phải là VERIFYING (Vàng), KHÔNG được là UNKNOWN!
    persons_in1, alerts_in1, _ = engine.process_frame([(201, bbox_inside)], roi_polygons=[polygon], current_time=103.0, frame_size=(1000, 1000))
    assert persons_in1[0].security_display_state == "VERIFYING", f"Expected VERIFYING on entry, got {persons_in1[0].security_display_state}"
    assert len(alerts_in1) == 0

    # Sau 1.5s trong ROI (t = 104.5s) -> Vẫn phải là VERIFYING (Vàng)!
    persons_in2, alerts_in2, _ = engine.process_frame([(201, bbox_inside)], roi_polygons=[polygon], current_time=104.5, frame_size=(1000, 1000))
    assert persons_in2[0].security_display_state == "VERIFYING"
    assert len(alerts_in2) == 0

    # Sau 2.1s trong ROI (t = 105.1s > grace period 2.0s) -> Hết giờ, chuyển UNKNOWN (Đỏ)!
    persons_in3, alerts_in3, _ = engine.process_frame([(201, bbox_inside)], roi_polygons=[polygon], current_time=105.1, frame_size=(1000, 1000))
    assert persons_in3[0].security_display_state == "UNKNOWN"
    assert len(alerts_in3) == 1
    assert alerts_in3[0].event_type == "UNKNOWN_PERSON"


def test_early_exit_fail_secure_triggers_unknown():
    """
    Kiểm tra cơ chế Phản xạ nhanh (Early Exit Fail-Secure):
    Kẻ gian chạy vội qua ROI vào phòng chỉ mất 0.8s khi chưa xác thực danh tính.
    Ngay khoảnh khắc rời khỏi ROI vào phòng, hệ thống lập tức chốt ĐỎ (UNKNOWN) và phát cảnh báo!
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, access_grace_period=2.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)
    bbox_in_room = BoundingBox(750, 700, 850, 850, confidence=0.9)

    # Frame 1: Bước vào ROI tại t = 100.0s -> VERIFYING
    persons1, alerts1, _ = engine.process_frame([(202, bbox_inside)], roi_polygons=[polygon], current_time=100.0, frame_size=(1000, 1000))
    assert persons1[0].security_display_state == "VERIFYING"
    assert len(alerts1) == 0

    # Frame 2: Tại t = 100.8s (mới chỉ 0.8s, chưa hết 2s!), chạy vọt ra khỏi ROI vào phòng
    # Hệ thống phải kích hoạt Early Exit: Chốt ĐỎ (UNKNOWN) và bắn cảnh báo ngay lập tức!
    persons2, alerts2, events2 = engine.process_frame([(202, bbox_in_room)], roi_polygons=[polygon], current_time=100.8, frame_size=(1000, 1000))
    assert persons2[0].security_display_state == "UNKNOWN", f"Expected UNKNOWN on early exit, got {persons2[0].security_display_state}"
    assert len(alerts2) == 1
    assert alerts2[0].event_type == "UNKNOWN_PERSON"
    assert "[UNKNOWN_PERSON]" in alerts2[0].details

    # Frame 3: Tiếp tục ở trong phòng ngoài ROI tại t = 101.5s -> Vẫn giữ nguyên UNKNOWN (Sticky State) không spam alert
    persons3, alerts3, _ = engine.process_frame([(202, bbox_in_room)], roi_polygons=[polygon], current_time=101.5, frame_size=(1000, 1000))
    assert persons3[0].security_display_state == "UNKNOWN"
    assert len(alerts3) == 0  # Cooldown ngăn duplicate


def test_immediate_authorized_on_face_match_in_roi():
    """
    Kiểm tra nhận diện thành công trong ROI:
    Khi người dùng vừa vào ROI (đang VERIFYING) và được ArcFace xác nhận hợp lệ sau 0.3s
    -> Khung lập tức chuyển sang AUTHORIZED (Xanh lá).
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, access_grace_period=2.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_inside = BoundingBox(450, 400, 550, 550, confidence=0.9)

    # Frame 1: Vào ROI tại t = 100.0s -> VERIFYING
    persons1, alerts1, _ = engine.process_frame([(203, bbox_inside)], roi_polygons=[polygon], current_time=100.0, frame_size=(1000, 1000))
    assert persons1[0].security_display_state == "VERIFYING"

    # Frame 2: Tại t = 100.3s, ArcFace quét được mặt SE194249 (Authorized)
    engine.associate_face(203, FaceDetectionResult(
        bbox=bbox_inside,
        score=0.92,
        matched_code="SE194249",
        matched_name="Nguyen Hoang Minh",
        is_authorized=True,
        access_checked=True,
        access_reason="Hop le"
    ))
    persons2, alerts2, events2 = engine.process_frame([(203, bbox_inside)], roi_polygons=[polygon], current_time=100.3, frame_size=(1000, 1000))
    assert persons2[0].security_display_state == "AUTHORIZED"
    assert len(alerts2) == 0
    # Ghi nhận sự kiện ENTRY với mã SE194249
    entry_events = [e for e in events2 if e.get("direction") == "ENTRY"]
    assert len(entry_events) == 1
    assert entry_events[0]["user_code"] == "SE194249"

def test_fast_jump_across_roi_with_track_switch_triggers_early_exit():
    """
    Kiem tra loi nhay vot qua ROI khien ByteTrack doi Track ID va khoang cach > 450px:
    - Frame 1: Nguoi la buoc vao ROI -> Track 301 (VERIFYING)
    - Frame 2: Nhay xa vao phong (680px > 450px threshold cu), ByteTrack doi thanh Track 302
    - He thong PHAI ke thua danh tinh qua Single Person Continuity, nhan dien Early Exit va ban canh bao UNKNOWN_PERSON!
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, access_grace_period=2.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    # 1920x1080: ROI pixel la X in [768, 1152], Y in [432, 648]
    # Bbox tai cua ROI (bottom_center = 900, 600 -> nam trong ROI)
    bbox_door = BoundingBox(850, 450, 950, 600, confidence=0.9)
    # Bbox nhay vao sau trong phong (center = 1450, 925) -> Khoang cach tam = sqrt((1450-900)^2 + (925-525)^2) approx 680px!
    bbox_deep_room = BoundingBox(1400, 850, 1500, 1000, confidence=0.9)

    # Frame 1: Track 301 buoc vao ROI luc t = 100.0s -> VERIFYING
    persons1, alerts1, _ = engine.process_frame([(301, bbox_door)], roi_polygons=[polygon], current_time=100.0, frame_size=(1920, 1080))
    assert persons1[0].security_display_state == "VERIFYING"
    assert len(alerts1) == 0

    # Frame 2: Nhay xa vao phong luc t = 100.5s, ByteTrack doi sang Track 302
    persons2, alerts2, _ = engine.process_frame([(302, bbox_deep_room)], roi_polygons=[polygon], current_time=100.5, frame_size=(1920, 1080))
    
    assert len(persons2) == 1
    p2 = persons2[0]
    assert p2.track_id == 302
    # Phai kich hoat Early Exit va chuyen sang UNKNOWN (Do)!
    assert p2.security_display_state == "UNKNOWN", f"Expected UNKNOWN, got {p2.security_display_state}"
    assert len(alerts2) == 1
    assert alerts2[0].event_type == "UNKNOWN_PERSON"
    assert "[UNKNOWN_PERSON]" in alerts2[0].details


def test_unauthorized_person_entering_roi_never_triggers_unknown_alert():
    """
    Kiem tra dung tinh huong nguoi dung thac mac:
    - Nguoi khong co quyen (SE194249) buoc chan vao ROI.
    - Ban dau o ngoai ROI chua detect duoc mat, khi chan vua cham ROI:
      He thong PHAI giu trang thai VERIFYING (Vang), TUYET DOI KHONG duoc ban UNKNOWN_PERSON truoc!
    - Khi ArcFace quet duoc mat SE194249 va Backend xac nhan UNAUTHORIZED:
      He thong chi ban DUY NHAT 1 canh bao UNAUTHORIZED_PERSON!
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, access_grace_period=1.0)
    polygon = RoiPolygonConfig(
        label="Cua Lab 1",
        vertices=[Point(0.4, 0.4), Point(0.6, 0.4), Point(0.6, 0.6), Point(0.4, 0.6)]
    )
    bbox_in_roi = BoundingBox(450, 450, 550, 600, confidence=0.9)

    # Frame 1: Vua cham chan vao ROI luc t = 100.0s.
    # Face Detector (YuNet) phat hien co mat nhung ArcFace chua match kip (matched_code = None, access_checked = False)
    face_unmatched = FaceDetectionResult(
        bbox=bbox_in_roi,
        score=0.85,
        matched_code=None,
        matched_name=None,
        is_authorized=False,
        access_checked=False
    )
    engine.associate_face(401, face_unmatched)

    persons1, alerts1, _ = engine.process_frame([(401, bbox_in_roi)], roi_polygons=[polygon], current_time=100.0, frame_size=(1000, 1000))
    # KHONG duoc ban UNKNOWN_PERSON! Trang thai phai la VERIFYING!
    assert len(alerts1) == 0, f"Expected 0 alerts in grace period, got {alerts1}"
    assert persons1[0].security_display_state == "VERIFYING"
    assert persons1[0].alert_unknown_sent is False

    # Frame 2: Luc t = 100.15s, ArcFace quet duoc SE194249, Backend dang kiem tra (access_checked = False)
    face_matched = FaceDetectionResult(
        bbox=bbox_in_roi,
        score=0.92,
        matched_code="SE194249",
        matched_name="Dang Nguyen Gia Minh",
        is_authorized=False,
        access_checked=False
    )
    engine.associate_face(401, face_matched)
    persons2, alerts2, _ = engine.process_frame([(401, bbox_in_roi)], roi_polygons=[polygon], current_time=100.15, frame_size=(1000, 1000))
    assert len(alerts2) == 0
    assert persons2[0].security_display_state == "VERIFYING"

    # Frame 3: Luc t = 100.25s, Backend phan hoi access_checked = True, is_authorized = False
    face_checked = FaceDetectionResult(
        bbox=bbox_in_roi,
        score=0.92,
        matched_code="SE194249",
        matched_name="Dang Nguyen Gia Minh",
        is_authorized=False,
        access_checked=True,
        access_reason="Khong co quyen ra vao khu vuc"
    )
    engine.associate_face(401, face_checked)
    persons3, alerts3, _ = engine.process_frame([(401, bbox_in_roi)], roi_polygons=[polygon], current_time=100.25, frame_size=(1000, 1000))

    # PHAI ban DUY NHAT 1 canh bao UNAUTHORIZED_PERSON!
    assert len(alerts3) == 1
    assert alerts3[0].event_type == "UNAUTHORIZED_PERSON"
    assert "SE194249" in alerts3[0].details
    assert persons3[0].security_display_state == "UNAUTHORIZED"
    assert persons3[0].alert_unauthorized_sent is True
    assert persons3[0].alert_unknown_sent is False


def test_early_hallway_recognition_prewarms_and_alerts_unauthorized_directly():
    """
    Test kịch bản nhận diện sớm ngoài hành lang:
    - Khi người ở ngoài hành lang (ngoài ROI), ArcFace đã quét được SE194249.
    - Quyền truy cập được pre-warm (is_authorized=False, reason='Cấp độ không đủ').
    - Khi bước chân chạm vào ROI, hệ thống PHÁT THẲNG cảnh báo UNAUTHORIZED_PERSON,
      hoàn toàn KHÔNG bị báo UNKNOWN_PERSON (Người lạ) trước!
    - Khi quay lưng đi ra ngoài, KHÔNG bị bắn cảnh báo lặp lại.
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, unauthorized_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cửa Phòng Lab 1",
        vertices=[Point(0.4, 0.7), Point(0.6, 0.7), Point(0.6, 0.85), Point(0.4, 0.85)]
    )
    # 1. Ngoài hành lang (y: 200..350, ngoài ROI)
    bbox_hallway = BoundingBox(450, 200, 550, 350, confidence=0.9)
    # Face đã được nhận diện và pre-warm thẩm quyền từ xa:
    face_prewarmed = FaceDetectionResult(
        bbox=bbox_hallway,
        score=0.94,
        matched_code="SE194249",
        matched_name="Nguyễn Hoàng Minh",
        is_authorized=False,
        access_checked=True,
        access_reason="Cấp độ không đủ"
    )
    engine.associate_face(501, face_prewarmed)
    persons1, alerts1, _ = engine.process_frame(
        [(501, bbox_hallway)],
        roi_polygons=[polygon],
        current_time=100.0,
        camera_code="CAM-LAB1",
        frame_size=(1000, 1000)
    )
    assert len(alerts1) == 0
    assert persons1[0].security_display_state == "NEUTRAL"

    # 2. Bước chân chạm vào ROI (y: 700..850)
    bbox_doorway = BoundingBox(450, 720, 550, 840, confidence=0.9)
    persons2, alerts2, _ = engine.process_frame(
        [(501, bbox_doorway)],
        roi_polygons=[polygon],
        current_time=100.5,
        camera_code="CAM-LAB1",
        frame_size=(1000, 1000)
    )
    # PHÁT THẲNG cảnh báo UNAUTHORIZED_PERSON ngay tại thềm cửa!
    assert len(alerts2) == 1
    assert alerts2[0].event_type == "UNAUTHORIZED_PERSON"
    assert "Nguyễn Hoàng Minh" in alerts2[0].details
    assert "Cấp độ không đủ" in alerts2[0].details
    assert "UNKNOWN" not in alerts2[0].event_type
    assert persons2[0].security_display_state == "UNAUTHORIZED"
    assert persons2[0].alert_unauthorized_sent is True

    # 3. Vào sâu trong phòng (ngoài ROI: y: 880..980)
    bbox_room = BoundingBox(450, 880, 550, 980, confidence=0.9)
    persons3, alerts3, _ = engine.process_frame(
        [(501, bbox_room)],
        roi_polygons=[polygon],
        current_time=102.0,
        camera_code="CAM-LAB1",
        frame_size=(1000, 1000)
    )
    assert len(alerts3) == 0
    assert persons3[0].security_display_state == "UNAUTHORIZED"

    # 4. 20s sau, quay lưng đi ra ngoài (chạm lại ROI ở 120.0s < 30s cooldown)
    persons4, alerts4, _ = engine.process_frame(
        [(501, bbox_doorway)],
        roi_polygons=[polygon],
        current_time=120.0,
        camera_code="CAM-LAB1",
        frame_size=(1000, 1000)
    )
    # KHÔNG bắn cảnh báo lặp lại vì đã gửi khi bước vào!
    assert len(alerts4) == 0
    assert persons4[0].security_display_state == "UNAUTHORIZED"


def test_late_resolution_in_room_emits_unauthorized_alert():
    """
    Test trường hợp lọt qua ROI lúc chưa có mặt (Early Exit -> UNKNOWN),
    nhưng khi vào trong phòng, camera soi thấy mặt là SE194249 (Cấp độ không đủ):
    - Hệ thống PHẢI phát ngay cảnh báo UNAUTHORIZED_PERSON với tên và lý do!
    - Khi đi ra ngoài, không phát cảnh báo lặp.
    """
    engine = FrameAnalysisEngine(unknown_alert_cooldown=30.0, unauthorized_alert_cooldown=30.0)
    polygon = RoiPolygonConfig(
        label="Cửa Phòng Lab 1",
        vertices=[Point(0.4, 0.7), Point(0.6, 0.7), Point(0.6, 0.85), Point(0.4, 0.85)]
    )
    # 1. Lướt qua ROI vào phòng nhanh (Early exit) -> Bị đánh dấu UNKNOWN
    bbox_doorway = BoundingBox(450, 720, 550, 840, confidence=0.9)
    engine.process_frame([(601, bbox_doorway)], roi_polygons=[polygon], current_time=100.0, camera_code="CAM-LAB1", frame_size=(1000, 1000))
    # Bước vào phòng lúc 100.3s (duration 0.3s >= 0.2s)
    bbox_room = BoundingBox(450, 880, 550, 980, confidence=0.9)
    _, alerts_exit, _ = engine.process_frame([(601, bbox_room)], roi_polygons=[polygon], current_time=100.3, camera_code="CAM-LAB1", frame_size=(1000, 1000))
    assert len(alerts_exit) == 1
    assert alerts_exit[0].event_type == "UNKNOWN_PERSON"

    # 2. Ở trong phòng (101.0s), camera soi thấy mặt là Nguyễn Hoàng Minh (SE194249) - Cấp độ không đủ:
    face_unauth = FaceDetectionResult(
        bbox=bbox_room,
        score=0.95,
        matched_code="SE194249",
        matched_name="Nguyễn Hoàng Minh",
        is_authorized=False,
        access_checked=True,
        access_reason="Cấp độ không đủ"
    )
    engine.associate_face(601, face_unauth)
    persons_upgraded, alerts_upgraded, _ = engine.process_frame(
        [(601, bbox_room)],
        roi_polygons=[polygon],
        current_time=101.0,
        camera_code="CAM-LAB1",
        frame_size=(1000, 1000)
    )
    # PHẢI nâng cấp và phát ngay cảnh báo UNAUTHORIZED_PERSON!
    assert len(alerts_upgraded) == 1
    assert alerts_upgraded[0].event_type == "UNAUTHORIZED_PERSON"
    assert "Nguyễn Hoàng Minh" in alerts_upgraded[0].details
    assert "Cấp độ không đủ" in alerts_upgraded[0].details
    assert persons_upgraded[0].security_display_state == "UNAUTHORIZED"
    assert persons_upgraded[0].alert_unauthorized_sent is True

    # 3. Khi quay lưng đi ra ngoài lúc 105.0s: KHÔNG bắn lặp lại
    _, alerts_out, _ = engine.process_frame([(601, bbox_doorway)], roi_polygons=[polygon], current_time=105.0, camera_code="CAM-LAB1", frame_size=(1000, 1000))
    assert len(alerts_out) == 0
