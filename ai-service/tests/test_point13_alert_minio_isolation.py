import time
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

from src.pipeline.video_pipeline import VideoPipeline
from src.core.entity import SecurityAlertEvent


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_alert_dispatched_even_when_minio_upload_fails(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Điểm 13 (POINT-13): Khi MinIO rớt mạng/lỗi upload ảnh bằng chứng (ném Exception),
    cảnh báo an ninh (SecurityAlertEvent) KHÔNG ĐƯỢC BỊ NUỐT.
    Hệ thống vẫn phải gọi mqtt_client.send_alert(alert) để đẩy sự cố vào
    SQLite Durable Outbox gửi về Backend/Broker.
    """
    pipeline = VideoPipeline(camera_code="CAM-POINT13-TEST")
    mock_mqtt_instance = pipeline.mqtt_client
    mock_mqtt_instance.send_alert.reset_mock()

    # Giả lập MinIO sập hoặc timeout khi upload
    pipeline.storage_service.upload_frame_evidence = MagicMock(
        side_effect=ConnectionError("MinIO connection timed out / service down")
    )

    alert = SecurityAlertEvent(
        camera_code="CAM-POINT13-TEST",
        event_type="PERSON_INTRUSION",
        track_id=1,
        confidence=0.92,
        details="Phát hiện đột nhập ngoài giờ"
    )
    frame = np.full((480, 640, 3), 128, dtype=np.uint8)

    # Thực thi _dispatch_alert_async
    pipeline._dispatch_alert_async(alert, frame)

    # Alert bắt buộc phải được gửi sang mqtt_client
    mock_mqtt_instance.send_alert.assert_called_once_with(alert)
    assert alert.image_url is None, "Khi upload MinIO thất bại, image_url phải là None thay vì crash"


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_alert_dispatched_within_timeout_when_minio_hangs(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-13: MinIO giả lập treo 10s thì từ lúc có alert đến lúc gọi send_alert < 1.5s,
    alert được gửi với image_url=None và upload nền vẫn tiếp tục.
    """
    pipeline = VideoPipeline(camera_code="CAM-TIMEOUT-TEST")
    mock_mqtt_instance = pipeline.mqtt_client
    mock_mqtt_instance.send_alert.reset_mock()

    def slow_upload(*args, **kwargs):
        time.sleep(10.0)
        return "http://localhost:9000/security-evidence/late_upload.jpg"

    pipeline.storage_service.upload_frame_evidence = MagicMock(side_effect=slow_upload)

    alert = SecurityAlertEvent(
        camera_code="CAM-TIMEOUT-TEST",
        event_type="UNAUTHORIZED_ACCESS",
        track_id=2,
        confidence=0.88,
        details="Không có quyền truy cập",
    )
    frame = np.full((480, 640, 3), 128, dtype=np.uint8)

    start_t = time.time()
    pipeline._dispatch_alert_async(alert, frame)
    elapsed = time.time() - start_t

    assert elapsed < 1.5, f"Thời gian gửi alert phải < 1.5s dù MinIO treo 10s! Thực tế: {elapsed:.3f}s"
    mock_mqtt_instance.send_alert.assert_called_once_with(alert)
    assert alert.image_url is None, "Khi quá hạn timeout 1.0s, alert phải được gửi với image_url=None"
