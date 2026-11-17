"""
Các lỗi tìm thấy khi review độc lập (13/11/2026):
- #3 update_roi(polygons=None) xoá ROI -> đồng bộ giờ ban đêm biến cả khung hình thành vùng giám sát
- #2 đổi danh tính A -> B: ENTRY(B) gửi 2 lần; reset cooldown ghi vào thuộc tính không ai đọc
- #4 (gỡ danh tính kế thừa) được kiểm tra trong test_identity_reverify.py (nhánh fix/identity-reverify)
- #1 người được khớp mặt muộn (sau grace 1.5s từ lúc vào ROI) bị cảnh báo UNAUTHORIZED khi quyền chưa về
Không cần model thật: HumanDetector / FaceDetector / FaceMatcher được mock như các test pipeline khác.
"""
import time
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import numpy as np

from src.config import settings
from src.core.entity import BoundingBox, FaceDetectionResult, Point
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.stream_worker import CameraStreamWorker
from src.pipeline.video_pipeline import VideoPipeline

FRAME = np.full((600, 800, 3), 120, dtype=np.uint8)
ROI = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]


# ---------------------------------------------------------------- #3 update_roi
def test_update_roi_none_keeps_existing_polygons():
    fake = SimpleNamespace(pipeline=MagicMock(), camera_code="CAM-T")
    CameraStreamWorker.update_roi(fake, after_hour_start="22:00", after_hour_end="06:00")
    fake.pipeline.set_roi_config.assert_not_called()
    assert fake.pipeline.analysis_engine.after_hour_start == "22:00"


def test_update_roi_empty_list_still_clears_roi():
    fake = SimpleNamespace(pipeline=MagicMock(), camera_code="CAM-T")
    CameraStreamWorker.update_roi(fake, polygons=[])
    fake.pipeline.set_roi_config.assert_called_once_with(polygons=[])


# ---------------------------------------------------------------- pipeline dựng với mock
def _pipeline():
    p = VideoPipeline(camera_code="CAM-FIX-TEST", enable_motion_gating=False)
    p.set_roi_polygon(ROI)
    fm = p.face_matcher
    fm.get_cached_access.return_value = None
    fm.get_camera_area_name.return_value = "Khu vực test"
    fm.is_camera_public.return_value = False
    fm.check_camera_access.return_value = (True, "Hợp lệ", "Khu vực test", "ACCESS_REQUEST")
    # Detector thật trả object MỚI mỗi lần gọi (pipeline ghi matched_code lên chính object này)
    p.face_detector.detect_best_face_in_crop.side_effect = lambda *a, **k: FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0), score=0.9
    )
    return p


def _drain(p):
    for ex in (p.access_executor, p.access_log_executor):
        ex.shutdown(wait=True)


def _direction_calls(fm, direction):
    """(user_code) của các lệnh check_camera_access theo hướng ENTRY/EXIT, theo đúng thứ tự gọi."""
    out = []
    for c in fm.check_camera_access.call_args_list:
        args, kwargs = c
        d = kwargs.get("event_direction", args[3] if len(args) > 3 else None)
        if d == direction:
            out.append(args[1])
    return out


# ---------------------------------------------------------------- #2 đổi danh tính
@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_identity_switch_in_roi_logs_single_entry_after_exit_and_resets_cooldown(*_):
    p = _pipeline()
    fm = p.face_matcher
    p.human_detector.detect_and_track.return_value = [(1, BoundingBox(100.0, 100.0, 300.0, 500.0))]

    fm.match_face.return_value = ("USER_A", "A", 0.75, True, "NORMAL_USER")
    p.process_frame(FRAME, current_time=0.0)
    p.process_frame(FRAME, current_time=0.5)  # ENTRY(A)
    p.analysis_engine.track_last_unauthorized_alert_time[1] = 6.0  # A vừa bị cảnh báo

    fm.match_face.return_value = ("USER_B", "B", 0.90, True, "NORMAL_USER")
    p.process_frame(FRAME, current_time=3.5)  # lần 1: chưa đổi
    fm.match_face.return_value = ("USER_B", "B", 0.92, True, "NORMAL_USER")
    p.process_frame(FRAME, current_time=7.0)  # lần 2: đổi A -> B
    assert 1 not in p.analysis_engine.track_last_unauthorized_alert_time, "Cooldown của A phải bị xoá cho B"
    p.process_frame(FRAME, current_time=7.5)
    p.process_frame(FRAME, current_time=8.0)
    _drain(p)

    entries_b = [c for c in _direction_calls(fm, "ENTRY") if c == "USER_B"]
    assert len(entries_b) == 1, f"ENTRY(B) phải đúng 1 lần, thực tế {len(entries_b)}"
    exits_a = [c for c in _direction_calls(fm, "EXIT") if c == "USER_A"]
    assert exits_a, "Phải ghi EXIT(A)"
    order = [args[1] for args, kwargs in fm.check_camera_access.call_args_list
             if kwargs.get("event_direction", args[3] if len(args) > 3 else None) in ("ENTRY", "EXIT")]
    assert order.index("USER_A", 1) < order.index("USER_B"), f"EXIT(A) phải trước ENTRY(B): {order}"


# ---------------------------------------------------------------- #1 grace tính từ lúc khớp mặt
def _late_identified_person(engine, decision_after: float):
    """Người đứng trong ROI (cả khung hình) chưa nhận ra; khớp mặt ở 2.6s; quyết định quyền về sau decision_after giây."""
    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    alerts, t0 = [], 1000.0
    for dt in (0.0, 0.5, 1.0, 1.6, 2.2):
        alerts += engine.process_frame([(1, bbox)], current_time=t0 + dt, frame_size=(800, 600))[1]
    face = FaceDetectionResult(bbox=bbox, score=0.9, matched_code="SE1", matched_name="Minh", matched_at=t0 + 2.6)
    engine.associate_face(1, face)
    for dt in (2.6, 2.8, 3.0, 3.2, 4.0, 4.5):
        if dt >= 2.6 + decision_after and not face.access_checked:
            face.access_checked, face.is_authorized, face.access_reason = True, True, "Hợp lệ"
        alerts += engine.process_frame([(1, bbox)], current_time=t0 + dt, frame_size=(800, 600))[1]
    return alerts, engine.active_tracks.get(1)


def test_late_identified_authorized_person_not_flagged_unauthorized():
    engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="00:00")
    alerts, person = _late_identified_person(engine, decision_after=0.12)
    types = [a.event_type for a in alerts]
    assert "UNAUTHORIZED_PERSON" not in types and "PERSON_INTRUSION" not in types, types
    assert types.count("UNKNOWN_PERSON") == 1, "Cảnh báo người lạ ở 1.5s (chưa nhận ra) vẫn giữ nguyên, đúng NFR 3s"
    assert person.security_display_state == "AUTHORIZED"


def test_late_identified_without_decision_after_unknown_verdict_does_not_alert_twice():
    # Một lượt vào ROI chỉ có một cảnh báo: người này đã bị chốt UNKNOWN ở 1.6s (chưa thấy mặt).
    # Nhận ra mặt ở 2.6s mà hết khoảng chờ vẫn chưa có quyết định quyền -> hiển thị UNAUTHORIZED, KHÔNG phát cảnh báo thứ hai,
    # chỉ gửi IDENTITY_UPDATE để Backend cập nhật sự cố người lạ đã tạo.
    engine = FrameAnalysisEngine(after_hour_start="00:00", after_hour_end="00:00")
    wait = float(settings.ACCESS_CHECK_TIMEOUT_SECONDS) + 0.2
    alerts, person = _late_identified_person(engine, decision_after=999)  # không bao giờ có quyết định
    assert [a.event_type for a in alerts] == ["UNKNOWN_PERSON", "IDENTITY_UPDATE"]
    assert alerts[1].identity_outcome == "UNAUTHORIZED"
    assert person.security_display_state == "UNAUTHORIZED"
    assert wait < 4.5 - 2.6, "Khung thời gian test phải dài hơn khoảng chờ"
