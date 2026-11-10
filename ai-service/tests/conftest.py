import os
import sys
import atexit
import shutil
import tempfile

# Phải đặt TRƯỚC mọi import src.*: settings đọc biến môi trường khi src.config được import lần đầu.
# Outbox của test nằm trong thư mục tạm (không ghi vào ai-service/data/), và không test nào kết nối broker thật.
_OUTBOX_DIR = tempfile.mkdtemp(prefix="icss-test-outbox-")
os.environ["MQTT_OUTBOX_PATH"] = os.path.join(_OUTBOX_DIR, "mqtt_outbox.sqlite3")
os.environ["MQTT_ENABLED"] = "false"
os.environ.setdefault("POSTGRES_PASSWORD", "testpass")
os.environ.setdefault("MINIO_ROOT_USER", "minioadmin")
os.environ.setdefault("MINIO_ROOT_PASSWORD", "minioadmin")
os.environ.setdefault("INTERNAL_SERVICE_KEY", "0123456789abcdef0123456789abcdef")
atexit.register(shutil.rmtree, _OUTBOX_DIR, ignore_errors=True)

import pytest


def pytest_configure(config):
    # Marker requires_model: test dựng VideoPipeline / CameraStreamWorker thật -> HumanDetector nạp YOLO (models/yolo26n.pt).
    # File trọng số *.pt bị .gitignore nên checkout trên CI không có; ultralytics sẽ tự tải từ GitHub (mạng ngoài, ~5 MB,
    # dễ chập chờn). Job ai-service-test trên CI chạy `pytest -m "not requires_model"`; máy dev có model thì chạy đủ.
    # Danh sách hiện tại (13 test):
    #   test_motion_gated_pipeline.py::TestMotionGatedPipeline (2 test)
    #   test_suppress_dual_warning_roi.py::TestSuppressDualWarningROI (3 test)
    #   test_p1_05_lazy_overlay.py (cả file, 3 test)
    #   test_point12_rtsp_timeouts_and_cleanup.py::test_rtsp_capture_configures_timeouts
    #   test_point12_rtsp_timeouts_and_cleanup.py::test_stream_worker_stop_cleanly_releases_even_on_exception
    #   test_roi_access_gate.py::test_roi_and_access_gate
    #   test_sticky_security_state.py::test_pipeline_sticky_state_with_track_switch
    #   test_point04_access_source_parsing.py::test_apply_access_decision_populates_face_info_access_source
    config.addinivalue_line(
        "markers",
        "requires_model: cần trọng số model thật (yolo26n.pt, không có trong git); bỏ qua trên CI",
    )


@pytest.fixture(autouse=True)
def _reset_mqtt_singletons():
    """Sau mỗi test: đóng và bỏ MQTT client / outbox dùng chung để test sau không dùng lại."""
    yield
    # Chỉ dọn module đã được import; không ép import src.* cho test không dùng tới
    mqtt_client = sys.modules.get("src.integration.mqtt_client")
    if mqtt_client is not None and mqtt_client._shared_client is not None:
        try:
            mqtt_client._shared_client.close()
        finally:
            mqtt_client._shared_client = None

    alert_outbox = sys.modules.get("src.integration.alert_outbox")
    if alert_outbox is not None and alert_outbox._outbox is not None:
        try:
            alert_outbox._outbox.close()
        finally:
            alert_outbox._outbox = None
