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
atexit.register(shutil.rmtree, _OUTBOX_DIR, ignore_errors=True)

import pytest


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
