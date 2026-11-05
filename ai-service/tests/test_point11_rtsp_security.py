from unittest.mock import MagicMock, patch
import pytest
from fastapi.testclient import TestClient
from src.main import app, settings
from src.utils.security import mask_rtsp_url, validate_snapshot_rtsp_url, UvicornTokenMaskingFilter, setup_uvicorn_access_log_filter
from src.pipeline.stream_worker import resolve_camera_rtsp_url


@pytest.fixture
def client():
    return TestClient(app)


def test_mask_rtsp_url():
    """Điểm 11: Che mật khẩu trong RTSP URL"""
    # Có mật khẩu
    url1 = "rtsp://admin:super_secret_pwd@192.168.1.50:554/stream1"
    assert mask_rtsp_url(url1) == "rtsp://admin:***@192.168.1.50:554/stream1"

    # rtsps có mật khẩu
    url2 = "rtsps://cam_user:p%40ssw0rd@camera.campus.edu/live"
    assert mask_rtsp_url(url2) == "rtsps://cam_user:***@camera.campus.edu/live"

    # Không có mật khẩu
    url3 = "rtsp://localhost:8554/cam01"
    assert mask_rtsp_url(url3) == "rtsp://localhost:8554/cam01"

    # URL rỗng
    assert mask_rtsp_url("") == ""


def test_validate_snapshot_rtsp_url_blocks_ssrf_and_invalid_schemes():
    """Điểm 11: validate_snapshot_rtsp_url chặn SSRF và các scheme không phải rtsp/rtsps"""
    # Hợp lệ
    assert validate_snapshot_rtsp_url("rtsp://192.168.1.100:554/ch0") is True
    assert validate_snapshot_rtsp_url("rtsps://cam.domain.com:322/live") is True
    assert validate_snapshot_rtsp_url("rtsp://admin:pwd@localhost:8554/live") is True

    # Chặn HTTP / HTTPS / File / Gopher
    assert validate_snapshot_rtsp_url("http://192.168.1.100/test") is False
    assert validate_snapshot_rtsp_url("file:///etc/passwd") is False
    assert validate_snapshot_rtsp_url("gopher://localhost:70/") is False

    # Chặn Cloud Metadata SSRF
    assert validate_snapshot_rtsp_url("rtsp://169.254.169.254/latest/meta-data") is False
    assert validate_snapshot_rtsp_url("rtsp://metadata.google.internal/computeMetadata/v1") is False


def test_snapshot_endpoint_validates_rtsp_url(client):
    """Điểm 11: /api/v1/cameras/snapshot trả 400 Bad Request cho URL độc hại / sai scheme"""
    headers = {"X-Service-Key": settings.INTERNAL_SERVICE_KEY}

    # Scheme http bị từ chối 400
    res_http = client.post(
        "/api/v1/cameras/snapshot",
        json={"rtsp_url": "http://169.254.169.254/latest/meta-data"},
        headers=headers
    )
    assert res_http.status_code == 400
    assert "rtsp" in res_http.json()["detail"].lower()

    # Cloud metadata IP bị từ chối 400
    res_ssrf = client.post(
        "/api/v1/cameras/snapshot",
        json={"rtsp_url": "rtsp://169.254.169.254:554/ch0"},
        headers=headers
    )
    assert res_ssrf.status_code == 400


def test_resolve_camera_rtsp_url_uses_settings_mediamtx():
    """Điểm 11: resolve_camera_rtsp_url dùng settings.MEDIAMTX_RTSP_URL làm base mặc định"""
    with patch.object(settings, "MEDIAMTX_RTSP_URL", "rtsp://mediamtx-edge.campus:8554"):
        resolved = resolve_camera_rtsp_url("CAM-TEST-01")
        assert resolved.startswith("rtsp://mediamtx-edge.campus:8554/"), f"Thực tế: {resolved}"


def test_uvicorn_token_masking_filter_masks_query_args():
    """Điểm 11: UvicornTokenMaskingFilter che giấu token= trong args của uvicorn.access"""
    f = UvicornTokenMaskingFilter()
    import logging
    record = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname="test.py",
        lineno=1,
        msg='%s - "%s %s HTTP/%s" %d',
        args=("127.0.0.1:51234", "GET", "/api/v1/stream/live-annotated?camera_code=CAM-001&token=1893456000.abc123xyz456", "1.1", 200),
        exc_info=None
    )
    result = f.filter(record)
    assert result is True
    formatted = record.getMessage()
    assert "1893456000.abc123xyz456" not in formatted, "Token bí mật không được xuất hiện trong access log!"
    assert "token=***" in formatted
    assert "camera_code=CAM-001" in formatted


def test_uvicorn_token_masking_filter_masks_direct_message():
    """Điểm 11: UvicornTokenMaskingFilter che giấu token= khi log record truyền thẳng string msg"""
    f = UvicornTokenMaskingFilter()
    import logging
    record = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname="test.py",
        lineno=1,
        msg='GET /api/v1/stream/live-annotated?token=secret_super_token&user=test HTTP/1.1',
        args=(),
        exc_info=None
    )
    result = f.filter(record)
    assert result is True
    formatted = record.getMessage()
    assert "secret_super_token" not in formatted
    assert "token=***" in formatted
    assert "user=test" in formatted


def test_uvicorn_access_logger_has_masking_filter():
    """Điểm 11: setup_uvicorn_access_log_filter gắn filter vào uvicorn.access"""
    import logging
    setup_uvicorn_access_log_filter()
    u_logger = logging.getLogger("uvicorn.access")
    assert any(isinstance(flt, UvicornTokenMaskingFilter) for flt in u_logger.filters)
