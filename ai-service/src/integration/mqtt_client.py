import os
import json
import time
import logging
from typing import Optional, Any
from ..core.entity import SecurityAlertEvent
from ..config import settings

try:
    import paho.mqtt.client as mqtt
except ImportError:
    mqtt = None

logger = logging.getLogger(__name__)

class SecurityMqttClient:
    """
    Module gửi sự kiện an ninh và telemetry thời gian thực qua Eclipse Mosquitto MQTT Broker.
    Hỗ trợ chế độ Safe Mode (tự động fallback nếu broker offline hoặc thiếu paho-mqtt để không làm đứt gãy luồng xử lý video).
    """
    def __init__(self, host: Optional[str] = None, port: Optional[int] = None):
        self.host = host or settings.MQTT_BROKER_HOST
        self.port = port or settings.MQTT_BROKER_PORT
        self.client: Optional[Any] = None
        self.is_connected = False
        self._init_client()

    def _init_client(self):
        if not getattr(settings, "MQTT_ENABLED", True):
            logger.info("ℹ️ MQTT integration đang ở trạng thái TẮT (MQTT_ENABLED=False).")
            return

        if mqtt is None:
            logger.warning("⚠️ Thư viện paho-mqtt chưa được cài đặt. Chạy ở chế độ Standalone / Mock.")
            return

        try:
            client_id = f"ai-service-{os.getpid()}-{int(time.time())}"
            try:
                self.client = mqtt.Client(
                    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                    client_id=client_id
                )
            except Exception:
                self.client = mqtt.Client(client_id=client_id)

            self.client.on_connect = self._on_connect
            self.client.on_disconnect = self._on_disconnect

            self.client.connect_async(self.host, self.port, keepalive=30)
            self.client.loop_start()
            logger.info(f"🔌 [MQTT Client] Đang kết nối tới MQTT Broker {self.host}:{self.port}...")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"⚠️ [MQTT Client] Không thể kết nối tới MQTT Broker ({self.host}:{self.port}): {e}. Chạy ở chế độ Standalone / Mock.")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        rc_code = rc if isinstance(rc, int) else getattr(rc, "value", 0)
        if rc_code == 0:
            self.is_connected = True
            logger.info(f"✅ [MQTT Client] Kết nối thành công tới MQTT Broker {self.host}:{self.port}")
        else:
            self.is_connected = False
            logger.warning(f"❌ [MQTT Client] Kết nối thất bại với mã rc: {rc}")

    def _on_disconnect(self, client, userdata, rc, properties=None):
        self.is_connected = False
        logger.warning(f"⚠️ [MQTT Client] Mất kết nối tới MQTT Broker (rc={rc})")

    def send_alert(self, event: SecurityAlertEvent, topic: Optional[str] = None) -> bool:
        """Gửi sự kiện cảnh báo an ninh lên MQTT topic (QoS 1)"""
        topic_name = topic or getattr(settings, "MQTT_TOPIC_ALERTS", "campus/alerts/incidents")
        event_dict = event.to_dict()
        payload = json.dumps(event_dict, ensure_ascii=False)

        if self.is_connected and self.client:
            try:
                self.client.publish(topic_name, payload=payload, qos=1)
                logger.info(f"📡 [MQTT] Đã gửi cảnh báo {event.event_type} (Track #{event.track_id}) tới topic {topic_name}")
                return True
            except Exception as e:
                logger.error(f"❌ [MQTT LỖI] Gửi message thất bại: {e}")
                return False
        else:
            logger.info(f"📢 [MOCK MQTT] Cảnh báo phát sinh: {payload}")
            return True

    def close(self):
        if self.client:
            try:
                self.client.loop_stop()
                self.client.disconnect()
            except Exception:
                pass
