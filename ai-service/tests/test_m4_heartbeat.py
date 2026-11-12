import re
import json
import time
from unittest.mock import MagicMock, patch
from src.config import settings
from src.integration.edge_agent import EdgeMqttAgent
from src.integration.mqtt_client import SecurityMqttClient

def test_edge_agent_collect_edge_status():
    """Kiểm tra EdgeMqttAgent thu thập trạng thái edge đúng schema: edge_id, status, ts (ms), cameras, gpu, version."""
    mock_worker = MagicMock()
    mock_worker.processed_fps = 24.5
    mock_worker.is_running = True

    agent = EdgeMqttAgent(
        mqtt_client_wrapper=MagicMock(),
        storage=MagicMock(),
        active_workers={"CAM-001": mock_worker}
    )

    status = agent.collect_edge_status("ONLINE")
    assert status["edge_id"] == settings.MQTT_CLIENT_ID
    assert status["status"] == "ONLINE"
    assert status["version"] == "1.0.0"
    assert "gpu" in status
    assert len(status["cameras"]) == 1
    assert status["cameras"][0]["camera_code"] == "CAM-001"
    assert status["cameras"][0]["fps"] == 24.5
    assert status["cameras"][0]["connected"] is True

    # ts format: YYYY-MM-DDTHH:MM:SS.sssZ
    iso_ms_pattern = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$"
    assert re.match(iso_ms_pattern, status["ts"])

def test_edge_agent_publish_status_and_lifecycle():
    """Kiểm tra publish_status gửi retained JSON lên topic campus/edge/status/{edge_id}."""
    mock_mqtt = MagicMock()
    agent = EdgeMqttAgent(
        mqtt_client_wrapper=mock_mqtt,
        storage=MagicMock(),
        active_workers={}
    )

    agent.publish_status("ONLINE")
    expected_topic = f"campus/edge/status/{settings.MQTT_CLIENT_ID}"
    mock_mqtt.publish_json.assert_called_once()
    topic, data = mock_mqtt.publish_json.call_args[0][:2]
    kwargs = mock_mqtt.publish_json.call_args[1]
    # Heartbeat ONLINE: retained + QoS 0 (không để broker xếp hàng heartbeat cũ cho Backend đang tắt)
    assert kwargs.get("retain") is True
    assert kwargs.get("qos") == 0
    assert topic == expected_topic
    assert data["status"] == "ONLINE"
    assert data["edge_id"] == settings.MQTT_CLIENT_ID

def test_mqtt_client_will_set_configured():
    """Kiểm tra SecurityMqttClient cấu hình Last Will (will_set) trước khi connect_async."""
    with patch.object(settings, "MQTT_ENABLED", True):
        with patch("paho.mqtt.client.Client") as MockClientClass:
            mock_paho = MagicMock()
            MockClientClass.return_value = mock_paho

            client = SecurityMqttClient(start_flusher=False)
            expected_status_topic = f"campus/edge/status/{settings.MQTT_CLIENT_ID}"

            # Xác nhận will_set đã được gọi
            mock_paho.will_set.assert_called_once()
            will_topic = mock_paho.will_set.call_args[0][0]
            assert will_topic == expected_status_topic

            # Kiểm tra retain và payload OFFLINE
            kwargs = mock_paho.will_set.call_args[1] if mock_paho.will_set.call_args[1] else {}
            if kwargs:
                assert kwargs.get("retain") is True
            else:
                assert mock_paho.will_set.call_args[0][3] is True  # retain parameter

            will_payload = json.loads(mock_paho.will_set.call_args.kwargs.get("payload") or mock_paho.will_set.call_args[1].get("payload") or mock_paho.will_set.call_args[0][1])
            assert will_payload["status"] == "OFFLINE"
            assert will_payload["edge_id"] == settings.MQTT_CLIENT_ID


def test_offline_status_published_with_qos1():
    """OFFLINE khi tắt chủ động phải QoS 1 để chắc chắn tới Backend."""
    from unittest.mock import MagicMock
    from src.integration.edge_agent import EdgeMqttAgent

    mock_mqtt = MagicMock()
    agent = EdgeMqttAgent(mqtt_client_wrapper=mock_mqtt, storage=MagicMock(), active_workers={})
    agent.publish_status("OFFLINE")
    kwargs = mock_mqtt.publish_json.call_args[1]
    assert kwargs.get("qos") == 1 and kwargs.get("retain") is True
