"""
NFR-05 (N2): không có đường lưu dạng rõ trên MinIO.

- StorageService.upload_bytes: luôn gửi SSE-S3; MinIO từ chối SSE -> trả False, chỉ 1 lần put_object, log ERROR [NFR-05]
  không chứa secret.
- VideoPipeline._dispatch_alert_async: outbox (alert_outbox.py) không thử lại upload ảnh, nên ảnh bằng chứng không lên
  được MinIO -> cảnh báo VẪN được gửi (NFR-06) nhưng không kèm ảnh; upload thành công -> cảnh báo kèm key ảnh.
"""
import logging
import time
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
from minio.error import S3Error
from minio.sse import SseS3

from src.config import settings
from src.core.entity import SecurityAlertEvent
from src.integration.storage_service import StorageService
from src.pipeline.video_pipeline import VideoPipeline

BUCKET = "security-evidence"


def _s3_error(code: str) -> S3Error:
    return S3Error(MagicMock(), code, "detail returned by minio", "/b/o", "req-id", "host-id")


@pytest.fixture
def storage():
    """StorageService không kết nối MinIO thật: client là mock, bucket coi như đã kiểm."""
    with patch.object(settings, "MINIO_ENABLED", False):
        svc = StorageService()
    svc.client = MagicMock()
    svc.is_connected = True
    svc._verified_buckets.add(BUCKET)
    return svc


def _nfr05_lines(caplog):
    return [r.getMessage() for r in caplog.records if r.levelno == logging.ERROR and "[NFR-05]" in r.getMessage()]


# ===== StorageService.upload_bytes =====

def test_upload_bytes_sends_sse_s3(storage):
    assert storage.upload_bytes(BUCKET, "2026-11-15/CAM-1/a.jpg", b"\x01\x02") is True

    storage.client.put_object.assert_called_once()
    assert isinstance(storage.client.put_object.call_args.kwargs["sse"], SseS3)


@pytest.mark.parametrize("code", ["NotImplemented", "KMS.NotFoundException"])
def test_upload_bytes_sse_rejected_fails_without_plaintext_retry(storage, caplog, code):
    storage.client.put_object.side_effect = _s3_error(code)

    with caplog.at_level(logging.ERROR, logger="src.integration.storage_service"):
        ok = storage.upload_bytes(BUCKET, "2026-11-15/CAM-1/a.jpg", b"\x01\x02")

    assert ok is False
    # Đúng 1 lần ghi, và lần đó có SSE: không còn lần ghi lại không mã hoá
    storage.client.put_object.assert_called_once()
    assert isinstance(storage.client.put_object.call_args.kwargs["sse"], SseS3)
    lines = _nfr05_lines(caplog)
    assert len(lines) == 1, caplog.text
    assert f"code={code}" in lines[0]
    assert "detail returned by minio" not in lines[0]
    for secret in (settings.MINIO_SECRET_KEY, settings.MINIO_ACCESS_KEY):
        assert secret not in caplog.text


def test_upload_bytes_other_error_is_not_reported_as_nfr05(storage, caplog):
    storage.client.put_object.side_effect = _s3_error("AccessDenied")

    with caplog.at_level(logging.ERROR, logger="src.integration.storage_service"):
        assert storage.upload_bytes(BUCKET, "2026-11-15/CAM-1/a.jpg", b"\x01") is False

    storage.client.put_object.assert_called_once()
    assert _nfr05_lines(caplog) == []
    assert "[MINIO LỖI]" in caplog.text


# ===== VideoPipeline: cảnh báo vẫn gửi khi ảnh không lên được MinIO =====

def _pipeline_with_key(camera_code: str, expected_key: str) -> VideoPipeline:
    pipeline = VideoPipeline(camera_code=camera_code)
    pipeline.mqtt_client.send_alert.reset_mock()
    object_name = expected_key.split("/", 1)[1]
    pipeline.storage_service.precompute_evidence_key = MagicMock(
        return_value=(object_name, expected_key, object_name.rsplit("/", 1)[1], "2026-11-15")
    )
    return pipeline


def _alert(camera_code: str) -> SecurityAlertEvent:
    return SecurityAlertEvent(
        camera_code=camera_code,
        event_type="PERSON_INTRUSION",
        track_id=1,
        confidence=0.9,
        details="Phát hiện đột nhập",
    )


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_alert_sent_with_image_when_upload_succeeds(mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet):
    key = "security-evidence/2026-11-15/CAM-NFR05-OK/PERSON_INTRUSION_Track1_120000.jpg"
    pipeline = _pipeline_with_key("CAM-NFR05-OK", key)
    pipeline.storage_service.upload_frame_evidence_with_key = MagicMock(return_value=key)
    alert = _alert("CAM-NFR05-OK")

    pipeline._dispatch_alert_async(alert, np.full((48, 64, 3), 128, dtype=np.uint8))

    pipeline.mqtt_client.send_alert.assert_called_once_with(alert)
    assert alert.image_url == key


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_alert_still_sent_without_image_when_minio_rejects_sse(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet, storage, caplog
):
    """
    Nối thật StorageService.upload_frame_evidence_with_key -> upload_bytes: MinIO từ chối SSE -> upload_bytes False ->
    (lưu local như trước) -> pipeline vẫn gửi cảnh báo, image_url = None, có log ERROR [NFR-05].
    """
    key = "security-evidence/2026-11-15/CAM-NFR05-ERR/PERSON_INTRUSION_Track1_120000.jpg"
    pipeline = _pipeline_with_key("CAM-NFR05-ERR", key)
    storage.client.put_object.side_effect = _s3_error("NotImplemented")
    storage.save_local_snapshot = MagicMock(return_value="/tmp/evidence_snapshots/x.jpg")
    pipeline.storage_service.upload_frame_evidence_with_key = storage.upload_frame_evidence_with_key
    alert = _alert("CAM-NFR05-ERR")

    with caplog.at_level(logging.ERROR):
        start = time.time()
        pipeline._dispatch_alert_async(alert, np.full((48, 64, 3), 128, dtype=np.uint8))
        elapsed = time.time() - start

    pipeline.mqtt_client.send_alert.assert_called_once_with(alert)
    assert alert.image_url is None, "ảnh không lên được MinIO -> cảnh báo không kèm ảnh"
    storage.client.put_object.assert_called_once()
    assert len(_nfr05_lines(caplog)) == 1
    assert elapsed < 1.3
