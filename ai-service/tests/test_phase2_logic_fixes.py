import time
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch
import pytest

from src.core.entity import BoundingBox, Point, FaceDetectionResult, RoiPolygonConfig
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline
from src.config import settings


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_upload_executor_isolation_prevents_io_deadlock(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Điểm 4: Phân tách upload_executor độc lập khỏi io_executor và access_executor.
    Tránh nguy cơ deadlock starvation khi task dispatch gọi lồng submit task upload.
    """
    pipeline = VideoPipeline(camera_code="CAM-PHASE2-TEST")

    # Xác minh 3 executor độc lập
    assert hasattr(pipeline, "access_executor"), "Phải có access_executor"
    assert hasattr(pipeline, "upload_executor"), "Phải có upload_executor riêng"
    assert hasattr(pipeline, "io_executor"), "Phải có io_executor riêng"

    assert pipeline.upload_executor is not pipeline.io_executor, "upload_executor và io_executor phải độc lập"
    assert pipeline.upload_executor is not pipeline.access_executor, "upload_executor và access_executor phải độc lập"

    # Dừng an toàn cả 3
    pipeline.stop()
    assert pipeline.upload_executor is None
    assert pipeline.io_executor is None
    assert pipeline.access_executor is None


def test_unauthorized_alert_flapping_and_cooldown():
    """
    Điểm 5: Chống flapping mép ROI (ROI boundary jitter) cho UNAUTHORIZED_PERSON.
    Khi người dùng vi phạm đứng ở mép ROI, centroid rung lắc ra vào liên tục
    qua các frame KHÔNG được sinh ra hàng loạt cảnh báo trùng lặp.
    Sau chu kỳ cooldown (30s), nếu tái xâm nhập thì mới kích hoạt lại cảnh báo.
    """
    engine = FrameAnalysisEngine(
        after_hour_start="22:00",
        after_hour_end="06:00",
        unauthorized_alert_cooldown=30.0
    )

    t0 = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    # ROI polygon: x: 100..300, y: 100..300
    roi_poly = [Point(100, 100), Point(300, 100), Point(300, 300), Point(100, 300)]

    # Face result không có quyền
    unauth_face = FaceDetectionResult(
        bbox=BoundingBox(120, 100, 180, 160),
        score=0.92,
        matched_code="STUDENT_NO_ACCESS",
        matched_name="Sinh Vien Khong Quyen",
        is_authorized=False,
        access_checked=True,
        access_reason="Không có thẩm quyền vào phòng Server"
    )
    engine.associate_face(track_id=1, face_result=unauth_face)

    # Frame 1 (t0): Bước chân vào TRONG ROI -> Phát cảnh báo UNAUTHORIZED_PERSON
    box_inside = BoundingBox(120, 150, 200, 250)  # bottom_center: (160, 250) -> TRONG ROI
    _, alerts_f1, _ = engine.process_frame(
        detected_tracks=[(1, box_inside)],
        camera_code="CAM-001",
        roi_polygon=roi_poly,
        current_time=t0
    )
    assert len(alerts_f1) == 1
    assert alerts_f1[0].event_type == "UNAUTHORIZED_PERSON"

    # Frame 2 (t0 + 0.2s): Centroid rung lắc 1 pixel ra NGOÀI ROI (mép cửa)
    box_outside = BoundingBox(80, 150, 99, 250)  # bottom_center: (89.5, 250) -> NGOÀI ROI
    _, alerts_f2, _ = engine.process_frame(
        detected_tracks=[(1, box_outside)],
        camera_code="CAM-001",
        roi_polygon=roi_poly,
        current_time=t0 + 0.2
    )
    assert len(alerts_f2) == 0

    # Frame 3 (t0 + 0.4s): Centroid rung lắc trở lại TRONG ROI
    # Nhờ cơ chế chống flapping, cờ alert_unauthorized_sent được bảo toàn -> KHÔNG sinh cảnh báo kép!
    _, alerts_f3, _ = engine.process_frame(
        detected_tracks=[(1, box_inside)],
        camera_code="CAM-001",
        roi_polygon=roi_poly,
        current_time=t0 + 0.4
    )
    assert len(alerts_f3) == 0, "Flapping mép ROI không được bắn cảnh báo trùng lặp!"

    # Frame 4 (t0 + 35s): Sau 35s (> 30s cooldown), đối tượng tái xâm nhập ROI -> Cho phép cảnh báo lại
    _, alerts_f4, _ = engine.process_frame(
        detected_tracks=[(1, box_inside)],
        camera_code="CAM-001",
        roi_polygon=roi_poly,
        current_time=t0 + 35.0
    )
    assert len(alerts_f4) == 1, "Sau khi hết cooldown 30s, tái xâm nhập phải sinh cảnh báo mới"
    assert alerts_f4[0].event_type == "UNAUTHORIZED_PERSON"


def test_public_area_roi_stranger_no_false_alert():
    """
    Điểm 6: Khu vực công cộng (Cổng chính - is_public_area=True) ban ngày.
    Người lạ (UNKNOWN) đi qua ROI cổng (quy tắc ENTRY_EXIT_TRACKING)
    tuyệt đối KHÔNG bị bắn cảnh báo UNKNOWN_PERSON.
    Chỉ khi bước vào vùng ROI có rule hạn chế ("RESTRICTED_ACCESS") thì mới cảnh báo.
    """
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    t_day = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    # Cấu hình ROI tracking thông thường tại cổng trường
    gate_roi = RoiPolygonConfig(
        label="Cổng chính - Luồng xe và người",
        alert_rules=["ENTRY_EXIT_TRACKING"],
        vertices=[Point(0, 0), Point(500, 0), Point(500, 500), Point(0, 500)]
    )

    box = BoundingBox(100, 100, 200, 200)

    # Frame 1: Người lạ xuất hiện trong ROI cổng trường
    engine.process_frame(
        detected_tracks=[(1, box)],
        camera_code="CAM-GATE",
        roi_polygons=[gate_roi],
        frame_size=(1000, 1000),
        is_public_area=True,
        current_time=t_day
    )

    # Sau 2.0s (> 1.5s face buffer): Vẫn ở trong ROI cổng
    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, box)],
        camera_code="CAM-GATE",
        roi_polygons=[gate_roi],
        frame_size=(1000, 1000),
        is_public_area=True,
        current_time=t_day + 2.0
    )

    # KHÔNG được bắn cảnh báo UNKNOWN_PERSON vì đây là khu vực công cộng ban ngày!
    assert len(alerts) == 0, f"Khu vực công cộng ban ngày không được báo động người lạ qua cổng! Nhận: {alerts}"

    # Bây giờ cấu hình vùng bốt bảo vệ có rule RESTRICTED_ACCESS
    booth_roi = RoiPolygonConfig(
        label="Bốt gác an ninh",
        alert_rules=["RESTRICTED_ACCESS"],
        vertices=[Point(0, 0), Point(500, 0), Point(500, 500), Point(0, 500)]
    )

    _, alerts_booth, _ = engine.process_frame(
        detected_tracks=[(1, box)],
        camera_code="CAM-GATE",
        roi_polygons=[booth_roi],
        frame_size=(1000, 1000),
        is_public_area=True,
        current_time=t_day + 4.0
    )

    # Vào bốt an ninh có quy tắc RESTRICTED_ACCESS thì PHẢI cảnh báo UNKNOWN_PERSON!
    assert len(alerts_booth) == 1
    assert alerts_booth[0].event_type == "UNKNOWN_PERSON"
