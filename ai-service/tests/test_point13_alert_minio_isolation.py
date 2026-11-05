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
def test_point13_fast_minio_upload_completes_before_alert_sent(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-13 (a): Khi MinIO nhanh (< ALERT_IMAGE_WAIT_SECONDS, mặc định 0.8s),
    ảnh đã upload xong và tồn tại trên MinIO trước khi send_alert được gọi.
    """
    pipeline = VideoPipeline(camera_code="CAM-FAST-TEST")
    mock_mqtt_instance = pipeline.mqtt_client
    mock_mqtt_instance.send_alert.reset_mock()

    expected_key = "security-evidence/2026-11-05/CAM-FAST-TEST/PERSON_INTRUSION_Track1_120000.jpg"
    pipeline.storage_service.precompute_evidence_key = MagicMock(
        return_value=(
            "2026-11-05/CAM-FAST-TEST/PERSON_INTRUSION_Track1_120000.jpg",
            expected_key,
            "PERSON_INTRUSION_Track1_120000.jpg",
            "2026-11-05"
        )
    )

    upload_tracker = {
        "upload_done": False,
        "upload_done_when_alert_sent": False,
        "alert_called": False,
    }

    def fast_upload(*args, **kwargs):
        time.sleep(0.02)  # upload nhanh 20ms
        upload_tracker["upload_done"] = True
        return expected_key

    pipeline.storage_service.upload_frame_evidence_with_key = MagicMock(side_effect=fast_upload)

    def record_send_alert(alert):
        upload_tracker["alert_called"] = True
        upload_tracker["upload_done_when_alert_sent"] = upload_tracker["upload_done"]

    mock_mqtt_instance.send_alert.side_effect = record_send_alert

    alert = SecurityAlertEvent(
        camera_code="CAM-FAST-TEST",
        event_type="PERSON_INTRUSION",
        track_id=1,
        confidence=0.95,
        details="Phát hiện người trong khu vực"
    )
    frame = np.full((480, 640, 3), 128, dtype=np.uint8)

    pipeline._dispatch_alert_async(alert, frame)

    assert upload_tracker["alert_called"] is True
    assert upload_tracker["upload_done_when_alert_sent"] is True, "Ảnh phải được upload xong lên MinIO trước khi send_alert được gọi!"
    assert alert.image_url == expected_key


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_point13_slow_minio_hangs_alert_sent_within_timeout(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-13 (b): Khi MinIO treo 10s:
    - Chờ tối đa ALERT_IMAGE_WAIT_SECONDS (0.8s) rồi gửi alert trong < 1.3s.
    - alert.image_url vẫn là key tính trước đúng.
    - Upload tiếp tục chạy ở nền (không crash hay tạo thread mới).
    """
    pipeline = VideoPipeline(camera_code="CAM-TIMEOUT-TEST")
    mock_mqtt_instance = pipeline.mqtt_client
    mock_mqtt_instance.send_alert.reset_mock()

    expected_key = "security-evidence/2026-11-05/CAM-TIMEOUT-TEST/UNAUTHORIZED_PERSON_Track2_123456.jpg"
    pipeline.storage_service.precompute_evidence_key = MagicMock(
        return_value=(
            "2026-11-05/CAM-TIMEOUT-TEST/UNAUTHORIZED_PERSON_Track2_123456.jpg",
            expected_key,
            "UNAUTHORIZED_PERSON_Track2_123456.jpg",
            "2026-11-05"
        )
    )

    def slow_upload(*args, **kwargs):
        time.sleep(10.0)
        return expected_key

    pipeline.storage_service.upload_frame_evidence_with_key = MagicMock(side_effect=slow_upload)

    alert = SecurityAlertEvent(
        camera_code="CAM-TIMEOUT-TEST",
        event_type="UNAUTHORIZED_PERSON",
        track_id=2,
        confidence=0.88,
        details="Không có quyền truy cập",
    )
    frame = np.full((480, 640, 3), 128, dtype=np.uint8)

    start_t = time.time()
    pipeline._dispatch_alert_async(alert, frame)
    elapsed = time.time() - start_t

    # Gửi alert sau thời gian chờ (mặc định 0.8s) nhưng phải < 1.3s
    assert elapsed < 1.3, f"Thời gian gửi alert phải < 1.3s dù MinIO treo 10s! Thực tế: {elapsed:.3f}s"
    mock_mqtt_instance.send_alert.assert_called_once_with(alert)
    assert alert.image_url == expected_key, "image_url phải luôn là key tính trước đúng"


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_point13_minio_error_alert_still_sent_with_local_fallback(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    POINT-13 (c): Khi MinIO upload lỗi (ném Exception):
    - Cảnh báo an ninh vẫn được gửi (không bị nuốt) với image_url là key tính trước.
    - Có fallback lưu snapshot cục bộ (save_local_snapshot).
    """
    pipeline = VideoPipeline(camera_code="CAM-POINT13-ERR")
    mock_mqtt_instance = pipeline.mqtt_client
    mock_mqtt_instance.send_alert.reset_mock()

    expected_key = "security-evidence/2026-11-05/CAM-POINT13-ERR/PERSON_INTRUSION_Track1_120000.jpg"
    pipeline.storage_service.precompute_evidence_key = MagicMock(
        return_value=(
            "2026-11-05/CAM-POINT13-ERR/PERSON_INTRUSION_Track1_120000.jpg",
            expected_key,
            "PERSON_INTRUSION_Track1_120000.jpg",
            "2026-11-05"
        )
    )
    pipeline.storage_service.upload_frame_evidence_with_key = MagicMock(
        side_effect=ConnectionError("MinIO connection timed out / service down")
    )
    pipeline.storage_service.save_local_snapshot = MagicMock(return_value="/tmp/fallback.jpg")

    alert = SecurityAlertEvent(
        camera_code="CAM-POINT13-ERR",
        event_type="PERSON_INTRUSION",
        track_id=1,
        confidence=0.92,
        details="Phát hiện đột nhập ngoài giờ"
    )
    frame = np.full((480, 640, 3), 128, dtype=np.uint8)

    pipeline._dispatch_alert_async(alert, frame)

    mock_mqtt_instance.send_alert.assert_called_once_with(alert)
    assert alert.image_url == expected_key
    # Đảm bảo fallback local được gọi
    pipeline.storage_service.save_local_snapshot.assert_called_once()
