import os
import sys
import time
import sqlite3
import types

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from src.config import settings
from src.core.entity import SecurityAlertEvent
from src.integration import mqtt_client as mqtt_client_module
from src.integration.alert_outbox import AlertOutbox
from src.integration.mqtt_client import SecurityMqttClient


class FakePublishInfo:
    def __init__(self, mid: int, rc: int = 0):
        self.mid = mid
        self.rc = rc


class FakePahoClient:
    """Thay paho Client: không mở socket, chỉ ghi lại các lần gọi."""

    def __init__(self, *args, **kwargs):
        self.publish_calls = []
        self.username_pw_calls = []
        self.publish_error = None
        self._next_mid = 0

    def username_pw_set(self, username, password=None):
        self.username_pw_calls.append((username, password))

    def connect_async(self, host, port, keepalive=60):
        pass

    def loop_start(self):
        pass

    def loop_stop(self):
        pass

    def disconnect(self):
        pass

    def publish(self, topic, payload=None, qos=0):
        if self.publish_error is not None:
            raise self.publish_error
        self._next_mid += 1
        self.publish_calls.append({"topic": topic, "payload": payload, "qos": qos, "mid": self._next_mid})
        return FakePublishInfo(self._next_mid)


def _fake_paho_module():
    return types.SimpleNamespace(
        Client=FakePahoClient,
        CallbackAPIVersion=types.SimpleNamespace(VERSION2="VERSION2"),
        MQTT_ERR_SUCCESS=0,
    )


@pytest.fixture
def outbox(tmp_path):
    box = AlertOutbox(str(tmp_path / "outbox.sqlite3"))
    yield box
    box.close()


@pytest.fixture
def make_client(monkeypatch, outbox):
    monkeypatch.setattr(mqtt_client_module, "mqtt", _fake_paho_module())
    monkeypatch.setattr(settings, "MQTT_ENABLED", True)
    monkeypatch.setattr(settings, "MQTT_PASSWORD", "")
    created = []

    def _make(start_flusher=False):
        client = SecurityMqttClient(host="localhost", port=1883, outbox=outbox, start_flusher=start_flusher)
        created.append(client)
        return client

    yield _make
    for client in created:
        client.close()


def _event(event_id="evt-1"):
    return SecurityAlertEvent(event_id=event_id, camera_code="CAM-TEST", event_type="UNKNOWN_PERSON")


def _last_error(outbox, event_id):
    conn = sqlite3.connect(outbox.path)
    try:
        return conn.execute("SELECT last_error FROM outbox WHERE event_id = ?", (event_id,)).fetchone()[0]
    finally:
        conn.close()


def test_send_alert_while_disconnected_only_enqueues(make_client, outbox):
    client = make_client()

    assert client.send_alert(_event()) is True

    assert outbox.count() == 1
    assert client.client.publish_calls == []


def test_send_alert_while_connected_publishes_qos1_and_puback_clears_outbox(make_client, outbox):
    client = make_client()
    client._on_connect(client.client, None, {}, 0)

    assert client.send_alert(_event()) is True

    calls = client.client.publish_calls
    assert len(calls) == 1
    assert calls[0]["qos"] == 1
    assert calls[0]["topic"] == settings.MQTT_TOPIC_ALERTS
    assert outbox.count() == 1

    client._on_publish(client.client, None, calls[0]["mid"], None, None)

    assert outbox.count() == 0


def test_publish_exception_keeps_row_with_last_error(make_client, outbox):
    client = make_client()
    client._on_connect(client.client, None, {}, 0)
    client.client.publish_error = RuntimeError("socket closed")

    assert client.send_alert(_event()) is True

    assert outbox.count() == 1
    assert _last_error(outbox, "evt-1") == "socket closed"


def test_on_connect_wakes_flusher_and_sends_pending_rows(make_client, monkeypatch, outbox):
    monkeypatch.setattr(settings, "MQTT_OUTBOX_FLUSH_SECONDS", 60.0)
    client = make_client(start_flusher=True)
    client.send_alert(_event("evt-a"))
    client.send_alert(_event("evt-b"))
    assert client.client.publish_calls == []

    client._on_connect(client.client, None, {}, 0)

    deadline = time.time() + 3
    while len(client.client.publish_calls) < 2 and time.time() < deadline:
        time.sleep(0.02)
    calls = client.client.publish_calls
    assert sorted(c["payload"].split('"event_id": "')[1].split('"')[0] for c in calls) == ["evt-a", "evt-b"]
    assert all(c["qos"] == 1 for c in calls)

    for c in calls:
        client._on_publish(client.client, None, c["mid"], None, None)
    assert outbox.count() == 0


def test_username_pw_set_called_only_when_password_configured(make_client, monkeypatch):
    without_password = make_client()
    assert without_password.client.username_pw_calls == []

    monkeypatch.setattr(settings, "MQTT_PASSWORD", "test-only-password")
    with_password = make_client()
    assert len(with_password.client.username_pw_calls) == 1
    assert with_password.client.username_pw_calls[0][0] == "icss-ai"


def test_disconnect_callback_accepts_paho_v2_signature(make_client):
    client = make_client()
    client._on_connect(client.client, None, {}, 0)

    client._on_disconnect(client.client, None, {}, 0, None)

    assert client.is_connected is False


@pytest.fixture
def fake_monotonic(monkeypatch):
    now = [1000.0]
    monkeypatch.setattr(mqtt_client_module.time, "monotonic", lambda: now[0])
    return now


def test_early_ack_older_than_10s_does_not_mark_new_publish_as_sent(make_client, outbox, fake_monotonic):
    client = make_client()
    client._on_connect(client.client, None, {}, 0)
    # PUBACK lạc cho mid 1, không có publish nào đang chờ -> lưu vào _early_acks
    client._on_publish(client.client, None, 1, None, None)

    fake_monotonic[0] += 10.5
    assert client.send_alert(_event()) is True

    calls = client.client.publish_calls
    assert calls[0]["mid"] == 1
    assert outbox.count() == 1
    assert 1 not in client._early_acks

    # PUBACK thật của lần publish mới mới được xoá cảnh báo khỏi outbox
    client._on_publish(client.client, None, 1, None, None)
    assert outbox.count() == 0


def test_early_ack_within_10s_marks_publish_as_sent(make_client, outbox, fake_monotonic):
    client = make_client()
    client._on_connect(client.client, None, {}, 0)
    client._on_publish(client.client, None, 1, None, None)

    fake_monotonic[0] += 5.0
    assert client.send_alert(_event()) is True

    assert client.client.publish_calls[0]["mid"] == 1
    assert outbox.count() == 0
    assert 1 not in client._early_acks


def test_recording_early_ack_prunes_entries_older_than_10s(make_client, fake_monotonic):
    client = make_client()
    client._on_publish(client.client, None, 1, None, None)
    client._on_publish(client.client, None, 2, None, None)

    fake_monotonic[0] += 10.5
    client._on_publish(client.client, None, 3, None, None)

    assert set(client._early_acks) == {3}
