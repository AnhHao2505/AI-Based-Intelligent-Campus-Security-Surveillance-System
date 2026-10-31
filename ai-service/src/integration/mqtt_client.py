import os
import json
import time
import logging
from typing import Optional, Any, Callable, List
from ..core.entity import SecurityAlertEvent
from ..config import settings

try:
    import paho.mqtt.client as mqtt
except ImportError:
    mqtt = None

logger = logging.getLogger(__name__)


class SecurityMqttClient:
    """
    Module kết nối hai chiều qua Eclipse Mosquitto MQTT Broker (Cloud-Edge Outbound-Only):
    - Chiều Edge -> Cloud: Gửi sự kiện an ninh (`campus/alerts/incidents`), metadata ghi hình (`campus/recordings/completed`),
      và phản hồi RPC (`campus/edge/rpc/reply/{correlationId}`).
    - Chiều Cloud -> Edge: Tự động subscribe các kênh cấu hình Retained và lệnh điều khiển khi kết nối/kết nối lại.
    """

    def __init__(self, host: Optional[str] = None, port: Optional[int] = None):
        self.host = host or settings.MQTT_BROKER_HOST
        self.port = port or settings.MQTT_BROKER_PORT
        self.client: Optional[Any] = None
        self.is_connected = False
        self._subscriptions: List[str] = []
        self._message_handler: Optional[Callable[[str, bytes], None]] = None
        self._init_client()

    def _init_client(self):
        if not getattr(settings, "MQTT_ENABLED", True):
            logger.info("ℹ️ MQTT integration đang ở trạng thái TẮT (MQTT_ENABLED=False).")
            return

        if mqtt is None:
            logger.warning("⚠️ Thư viện paho-mqtt chưa được cài đặt. Chạy ở chế độ Standalone / Mock.")
            return

        try:
            client_id = f"ai-edge-{os.getpid()}-{int(time.time() * 1000) % 100000}"
            try:
                self.client = mqtt.Client(
                    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                    client_id=client_id
                )
            except Exception:
                self.client = mqtt.Client(client_id=client_id)

            self.client.on_connect = self._on_connect
            self.client.on_disconnect = self._on_disconnect
            self.client.on_message = self._on_message

            self.client.connect_async(self.host, self.port, keepalive=30)
            self.client.loop_start()
            logger.info(f"🔌 [MQTT Client] Đang kết nối Outbound tới MQTT Broker {self.host}:{self.port}...")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"⚠️ [MQTT Client] Không thể kết nối tới MQTT Broker ({self.host}:{self.port}): {e}.")

    def register_edge_subscriptions(self, topics: List[str], handler: Callable[[str, bytes], None]):
        """Đăng ký danh sách topic Cloud->Edge và hàm callback xử lý (tự động re-subscribe khi reconnect)."""
        self._subscriptions = list(topics)
        self._message_handler = handler
        if self.is_connected and self.client:
            for t in self._subscriptions:
                try:
                    self.client.subscribe(t, qos=1)
                    logger.info(f"📡 [MQTT Edge] Đã subscribe topic: {t}")
                except Exception as e:
                    logger.warning(f"⚠️ [MQTT Edge] Lỗi subscribe {t}: {e}")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        rc_code = rc if isinstance(rc, int) else getattr(rc, "value", 0)
        if rc_code == 0:
            self.is_connected = True
            logger.info(f"✅ [MQTT Client] Kết nối thành công tới MQTT Broker {self.host}:{self.port}")
            for t in self._subscriptions:
                try:
                    client.subscribe(t, qos=1)
                    logger.info(f"📡 [MQTT Edge] Auto-subscribed topic: {t}")
                except Exception as e:
                    logger.warning(f"⚠️ [MQTT Edge] Không thể subscribe {t}: {e}")
        else:
            self.is_connected = False
            logger.warning(f"❌ [MQTT Client] Kết nối thất bại với mã rc: {rc}")

    def _on_disconnect(self, client, userdata, rc, properties=None):
        self.is_connected = False
        logger.warning(f"⚠️ [MQTT Client] Mất kết nối tới MQTT Broker (rc={rc})")

    def _on_message(self, client, userdata, msg):
        if self._message_handler is not None and msg is not None:
            try:
                self._message_handler(msg.topic, msg.payload)
            except Exception as e:
                logger.error(f"❌ [MQTT Edge] Lỗi xử lý message từ topic {getattr(msg, 'topic', '?')}: {e}")

    def publish_json(self, topic: str, data: dict, qos: int = 1, retain: bool = False) -> bool:
        """Gửi payload JSON bất kỳ lên MQTT topic."""
        payload = json.dumps(data, ensure_ascii=False)
        if self.is_connected and self.client:
            try:
                self.client.publish(topic, payload=payload, qos=qos, retain=retain)
                return True
            except Exception as e:
                logger.error(f"❌ [MQTT LỖI] Publish tới {topic} thất bại: {e}")
                return False
        logger.debug(f"📢 [MOCK MQTT -> {topic}] {payload}")
        return True

    def send_alert(self, event: SecurityAlertEvent, topic: Optional[str] = None) -> bool:
        """Gửi sự kiện cảnh báo an ninh lên MQTT topic (QoS 1)"""
        topic_name = topic or getattr(settings, "MQTT_TOPIC_ALERTS", "campus/alerts/incidents")
        ok = self.publish_json(topic_name, event.to_dict(), qos=1, retain=False)
        if ok:
            logger.info(f"📡 [MQTT] Đã gửi cảnh báo {event.event_type} (Track #{event.track_id}) tới topic {topic_name}")
        return ok

    def publish_recording_event(self, payload: dict) -> bool:
        """Đồng bộ metadata file video vừa chốt (hoặc vừa xoay vòng xóa) về Cloud Backend CSDL."""
        topic_name = getattr(settings, "MQTT_TOPIC_RECORDINGS", "campus/recordings/completed")
        ok = self.publish_json(topic_name, payload, qos=1, retain=False)
        if ok:
            logger.info(
                f"📡 [MQTT Recording] Đã đồng bộ {payload.get('event_type')} -> {payload.get('object_key')} "
                f"({payload.get('duration_seconds', 0)}s)"
            )
        return ok

    def close(self):
        if self.client:
            try:
                self.client.loop_stop()
                self.client.disconnect()
            except Exception:
                pass


# Global singleton MQTT client dùng chung cho Edge Agent & Recorders
edge_mqtt_client = SecurityMqttClient()
