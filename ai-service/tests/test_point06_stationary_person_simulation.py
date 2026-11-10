from unittest.mock import MagicMock, patch
import numpy as np
import pytest
from src.core.entity import BoundingBox, Point, RoiPolygonConfig
from src.core.zone_state_machine import ZoneStateMachine, ZoneState
from src.core.motion_detector import MotionDetector
from src.pipeline.video_pipeline import VideoPipeline


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_stationary_person_recovered_by_yolo_heartbeat(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Điểm 6: Khi một người ngồi yên bị MOG2 hấp thụ vào background (has_motion = False)
    và sau đó ZoneStateMachine rơi về IDLE (motion gating tắt YOLO),
    cứ mỗi MOTION_GATING_HEARTBEAT_SECONDS (mặc định 4.0s), pipeline phải kích hoạt
    1 frame YOLO heartbeat thăm dò.
    Nếu phát hiện thấy người trong ROI, state_machine phải lập tức chuyển về ZONE_OCCUPIED,
    đánh thức lại Deep Learning liên tục cho các frame tiếp theo.
    """
    pipeline = VideoPipeline(
        camera_code="CAM-POINT06",
        enable_motion_gating=True
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = None
    pipeline.face_matcher.get_camera_area_name.return_value = "Phòng Server"

    # Cấu hình ROI ở trung tâm khung hình (chân người ở (300, 350) sẽ nằm trong ROI)
    roi = RoiPolygonConfig(
        label="Server-Room",
        vertices=[Point(200, 100), Point(400, 100), Point(400, 400), Point(200, 400)]
    )
    pipeline.set_roi_config([roi])
    pipeline.heartbeat_interval_seconds = 4.0

    person_bbox = BoundingBox(x1=250.0, y1=150.0, x2=350.0, y2=350.0)
    person_frame = np.full((480, 640, 3), 120, dtype=np.uint8)

    # 1. Ban đầu người bước vào tại t=0.0: YOLO phát hiện, state = ZONE_OCCUPIED
    pipeline.human_detector.detect_and_track.return_value = [(1, person_bbox)]
    pipeline.process_frame(person_frame, current_time=0.0)
    pipeline.state_machine._state = ZoneState.ZONE_OCCUPIED

    # 2. Người ngồi yên, MOG2 hấp thụ người -> giả lập motion_detector trả has_motion = False
    pipeline.motion_detector.detect = MagicMock(return_value=MagicMock(
        has_motion=False, is_tampering=False, is_lighting_spike=False, motion_boxes=[]
    ))

    # 3. Giả lập người cúi gục/khuất khiến YOLO trượt tạm thời tại t=10.0 -> rơi vào COOLDOWN -> IDLE
    pipeline.human_detector.detect_and_track.return_value = []
    pipeline.process_frame(person_frame, current_time=10.0)
    # Chờ hết cooldown (5s) -> t=16.0s về IDLE
    pipeline.process_frame(person_frame, current_time=16.0)
    assert pipeline.state_machine.state == ZoneState.IDLE, "Pipeline phải rơi về IDLE khi không có motion và YOLO trượt"
    assert pipeline.state_machine.should_run_deep_learning is False

    # 4. Người ngồi yên tại chỗ trong ROI, YOLO có thể nhìn thấy lại:
    pipeline.human_detector.detect_and_track.return_value = [(1, person_bbox)]

    # Tại t=18.0s (mới 2s trôi qua < 4.0s heartbeat kể từ t=16.0s):
    pipeline.human_detector.detect_and_track.reset_mock()
    pipeline.process_frame(person_frame, current_time=18.0)
    # Chưa đủ 4s heartbeat: YOLO KHÔNG được gọi, state vẫn là IDLE
    pipeline.human_detector.detect_and_track.assert_not_called()
    assert pipeline.state_machine.state == ZoneState.IDLE

    # 5. Tại t=20.5s (đã qua 4.5s >= 4.0s heartbeat kể từ t=16.0s):
    # Heartbeat probe kích hoạt! YOLO phải được gọi thăm dò
    pipeline.process_frame(person_frame, current_time=20.5)
    pipeline.human_detector.detect_and_track.assert_called_once()

    # Vì người đang ngồi trong ROI, state machine phải lập tức trở lại ZONE_OCCUPIED!
    assert pipeline.state_machine.state == ZoneState.ZONE_OCCUPIED, (
        f"Lỗi: Heartbeat phát hiện người trong ROI nhưng state machine vẫn ở {pipeline.state_machine.state}"
    )
    assert pipeline.state_machine.should_run_deep_learning is True

    # 6. Tại frame kế tiếp t=20.6s: State machine đang ZONE_OCCUPIED nên Deep Learning tiếp tục chạy liên tục
    pipeline.human_detector.detect_and_track.reset_mock()
    pipeline.process_frame(person_frame, current_time=20.6)
    pipeline.human_detector.detect_and_track.assert_called_once()


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_heartbeat_starts_in_idle_with_simulated_timestamps(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Điểm 6: Kiểm tra heartbeat probe sử dụng thống nhất nguồn thời gian current_time,
    kể cả khi pipeline khởi động trực tiếp ở trạng thái IDLE với timestamp mô phỏng (t=100.0s).
    """
    pipeline = VideoPipeline(
        camera_code="CAM-IDLE-SIM",
        enable_motion_gating=True
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = None
    pipeline.heartbeat_interval_seconds = 4.0
    pipeline.motion_detector.detect = MagicMock(return_value=MagicMock(
        has_motion=False, is_tampering=False, is_lighting_spike=False, motion_boxes=[]
    ))
    pipeline.human_detector.detect_and_track.return_value = []

    frame = np.full((480, 640, 3), 100, dtype=np.uint8)

    # Frame 1: Khởi động ở IDLE tại t=100.0s
    pipeline.process_frame(frame, current_time=100.0)
    pipeline.human_detector.detect_and_track.assert_not_called()

    # Frame 2: Tại t=102.0s (mới 2s < 4s) -> không chạy probe
    pipeline.process_frame(frame, current_time=102.0)
    pipeline.human_detector.detect_and_track.assert_not_called()

    # Frame 3: Tại t=104.5s (đã qua 4.5s >= 4.0s) -> Heartbeat probe kích hoạt!
    pipeline.process_frame(frame, current_time=104.5)
    pipeline.human_detector.detect_and_track.assert_called_once()
