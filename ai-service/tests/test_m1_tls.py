import pytest
from unittest.mock import patch, MagicMock
from src.config import settings
from src.integration.mqtt_client import SecurityMqttClient

def test_mqtt_tls_disabled_by_default():
    """Mặc định TLS tắt, transport là tcp, không gọi tls_set."""
    with patch.object(settings, "MQTT_ENABLED", True), \
         patch.object(settings, "MQTT_TLS_ENABLED", False), \
         patch.object(settings, "MQTT_TRANSPORT", "tcp"):
        with patch("paho.mqtt.client.Client") as MockClientClass:
            mock_paho = MagicMock()
            MockClientClass.return_value = mock_paho

            client = SecurityMqttClient(start_flusher=False)

            # Xác nhận tls_set và tls_insecure_set không bị gọi
            mock_paho.tls_set.assert_not_called()
            mock_paho.tls_insecure_set.assert_not_called()

            # Xác nhận transport truyền vào Client là tcp
            call_kwargs = MockClientClass.call_args.kwargs
            assert call_kwargs.get("transport") == "tcp"

def test_mqtt_tls_enabled_with_ca_path():
    """Khi bật MQTT_TLS_ENABLED và cấu hình ca_path, gọi tls_set với ca_certs tương ứng."""
    test_ca = "mosquitto/certs/ca.crt"
    with patch.object(settings, "MQTT_ENABLED", True), \
         patch.object(settings, "MQTT_TLS_ENABLED", True), \
         patch.object(settings, "MQTT_TLS_CA_PATH", test_ca), \
         patch.object(settings, "MQTT_TLS_INSECURE", False):
        with patch("paho.mqtt.client.Client") as MockClientClass:
            mock_paho = MagicMock()
            MockClientClass.return_value = mock_paho

            client = SecurityMqttClient(start_flusher=False)

            # Xác nhận tls_set được gọi đúng ca_certs
            mock_paho.tls_set.assert_called_once_with(ca_certs=test_ca)
            mock_paho.tls_insecure_set.assert_not_called()

def test_mqtt_tls_enabled_insecure():
    """Khi bật MQTT_TLS_INSECURE=True, gọi tls_insecure_set(True) cho môi trường dev."""
    with patch.object(settings, "MQTT_ENABLED", True), \
         patch.object(settings, "MQTT_TLS_ENABLED", True), \
         patch.object(settings, "MQTT_TLS_CA_PATH", None), \
         patch.object(settings, "MQTT_TLS_INSECURE", True):
        with patch("paho.mqtt.client.Client") as MockClientClass:
            mock_paho = MagicMock()
            MockClientClass.return_value = mock_paho

            client = SecurityMqttClient(start_flusher=False)

            mock_paho.tls_set.assert_called_once_with(ca_certs=None)
            mock_paho.tls_insecure_set.assert_called_once_with(True)

def test_mqtt_transport_websockets():
    """Khi cấu hình MQTT_TRANSPORT="websockets", paho Client nhận transport="websockets"."""
    with patch.object(settings, "MQTT_ENABLED", True), \
         patch.object(settings, "MQTT_TRANSPORT", "websockets"):
        with patch("paho.mqtt.client.Client") as MockClientClass:
            mock_paho = MagicMock()
            MockClientClass.return_value = mock_paho

            client = SecurityMqttClient(start_flusher=False)

            call_kwargs = MockClientClass.call_args.kwargs
            assert call_kwargs.get("transport") == "websockets"
