import os
import json
import time
import logging
import threading
from typing import Dict, Optional, Any, Set
from ..core.entity import SecurityAlertEvent
from ..config import settings
from .alert_outbox import AlertOutbox, get_outbox

try:
    import paho.mqtt.client as mqtt
except ImportError:
    mqtt = None

logger = logging.getLogger(__name__)

# Số dòng outbox gửi mỗi lượt trong luồng flush
_FLUSH_BATCH_SIZE = 200
# PUBACK về sớm chỉ được ghép với publish() trong khoảng này; cũ hơn thì coi như không khớp (mid đã bị dùng lại)
_EARLY_ACK_TTL_SECONDS = 10.0

class SecurityMqttClient:
    """
    Module gửi sự kiện an ninh và telemetry thời gian thực qua Eclipse Mosquitto MQTT Broker.
    Cảnh báo luôn được lưu vào outbox SQLite trước khi gửi, và chỉ xoá khi broker xác nhận (PUBACK):
    broker offline hay thiếu paho-mqtt thì cảnh báo nằm chờ trong outbox, không làm đứt luồng xử lý video.
    """
    def __init__(self, host: Optional[str] = None, port: Optional[int] = None,
                 outbox: Optional[AlertOutbox] = None, start_flusher: bool = True):
        self.host = host or settings.MQTT_BROKER_HOST
        self.port = port or settings.MQTT_BROKER_PORT
        self.client: Optional[Any] = None
        self.is_connected = False
        self.outbox = outbox or get_outbox()
        self._mid_to_event: Dict[int, str] = {}
        # mid -> time.monotonic() lúc nhận PUBACK về sớm
        self._early_acks: Dict[int, float] = {}
        self._mid_lock = threading.Lock()
        self._send_lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._flush_thread: Optional[threading.Thread] = None
        self._init_client()
        if start_flusher:
            self._flush_thread = threading.Thread(target=self._flush_loop, name="mqtt-outbox-flush", daemon=True)
            self._flush_thread.start()

    def _init_client(self):
        if not getattr(settings, "MQTT_ENABLED", True):
            logger.info("ℹ️ MQTT integration đang ở trạng thái TẮT (MQTT_ENABLED=False).")
            return

        if mqtt is None:
            logger.warning("⚠️ Thư viện paho-mqtt chưa được cài đặt. Cảnh báo chỉ được lưu vào outbox, chưa gửi đi.")
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
            self.client.on_publish = self._on_publish

            if settings.MQTT_PASSWORD:
                self.client.username_pw_set(settings.MQTT_USERNAME, settings.MQTT_PASSWORD)

            self.client.connect_async(self.host, self.port, keepalive=30)
            self.client.loop_start()
            logger.info(f"🔌 [MQTT Client] Đang kết nối tới MQTT Broker {self.host}:{self.port} (user: {settings.MQTT_USERNAME if settings.MQTT_PASSWORD else '<ẩn danh>'})...")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"⚠️ [MQTT Client] Không thể kết nối tới MQTT Broker ({self.host}:{self.port}): {e}. Cảnh báo được giữ trong outbox.")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        rc_code = rc if isinstance(rc, int) else getattr(rc, "value", 0)
        if rc_code == 0:
            self.is_connected = True
            logger.info(f"✅ [MQTT Client] Kết nối thành công tới MQTT Broker {self.host}:{self.port}")
            # Gửi ngay các cảnh báo tồn trong outbox, không chờ tới chu kỳ flush
            self._wake.set()
        else:
            self.is_connected = False
            logger.warning(f"❌ [MQTT Client] Kết nối thất bại với mã rc: {rc}")

    def _on_disconnect(self, client, userdata, *args):
        # paho VERSION2 gọi (disconnect_flags, reason_code, properties); paho cũ gọi (rc[, properties])
        rc = args[1] if len(args) >= 3 else (args[0] if args else None)
        self.is_connected = False
        logger.warning(f"⚠️ [MQTT Client] Mất kết nối tới MQTT Broker (rc={rc})")

    def _on_publish(self, client, userdata, mid, reason_code=None, properties=None):
        """Broker đã xác nhận (PUBACK, QoS 1) -> xoá cảnh báo khỏi outbox."""
        with self._mid_lock:
            event_id = self._mid_to_event.pop(mid, None)
            if event_id is None:
                # PUBACK về trước khi publish() kịp trả mid; _publish_row sẽ xử lý
                now = time.monotonic()
                for stale_mid in [m for m, ts in self._early_acks.items() if now - ts > _EARLY_ACK_TTL_SECONDS]:
                    del self._early_acks[stale_mid]
                self._early_acks[mid] = now
                return
        if reason_code is not None and getattr(reason_code, "is_failure", False):
            self.outbox.mark_attempt(event_id, f"PUBACK lỗi: {reason_code}")
            logger.warning(f"⚠️ [MQTT] Broker từ chối cảnh báo {event_id}: {reason_code}")
            return
        self.outbox.mark_sent(event_id)

    def _publish_row(self, event_id: str, topic: str, payload: str) -> bool:
        try:
            info = self.client.publish(topic, payload=payload, qos=1)
            rc = getattr(info, "rc", 0)
            with self._mid_lock:
                acked_at = self._early_acks.pop(info.mid, None)
                if acked_at is not None and time.monotonic() - acked_at <= _EARLY_ACK_TTL_SECONDS:
                    acked = True
                else:
                    self._mid_to_event[info.mid] = event_id
                    acked = False
            if acked:
                self.outbox.mark_sent(event_id)
                return True
            if rc != getattr(mqtt, "MQTT_ERR_SUCCESS", 0):
                self.outbox.mark_attempt(event_id, f"publish rc={rc}")
                return False
            self.outbox.mark_attempt(event_id)
            return True
        except Exception as e:
            logger.error(f"❌ [MQTT LỖI] Gửi message {event_id} thất bại: {e}")
            self.outbox.mark_attempt(event_id, str(e))
            return False

    def send_alert(self, event: SecurityAlertEvent, topic: Optional[str] = None) -> bool:
        """
        Gửi sự kiện cảnh báo an ninh lên MQTT topic (QoS 1).
        Trả True nghĩa là cảnh báo đã được lưu bền vào outbox (sẽ gửi tới khi broker xác nhận),
        không có nghĩa là broker đã nhận.
        """
        topic_name = topic or getattr(settings, "MQTT_TOPIC_ALERTS", "campus/alerts/incidents")
        event_dict = event.to_dict()
        payload = json.dumps(event_dict, ensure_ascii=False)

        try:
            self.outbox.enqueue(event.event_id, topic_name, payload)
        except Exception as e:
            logger.error(f"❌ [MQTT Outbox] Không lưu được cảnh báo {event.event_id} vào outbox: {e}")
            if self.is_connected and self.client:
                try:
                    self.client.publish(topic_name, payload=payload, qos=1)
                except Exception as pub_err:
                    logger.error(f"❌ [MQTT LỖI] Gửi message thất bại: {pub_err}")
            return False

        if self.client is None:
            logger.warning(f"⚠️ [MQTT] MQTT tắt hoặc thiếu paho-mqtt: cảnh báo {event.event_type} ({event.event_id}) chỉ được lưu vào outbox, CHƯA gửi tới backend.")
            return True

        if self.is_connected:
            with self._send_lock:
                if self._publish_row(event.event_id, topic_name, payload):
                    logger.info(f"📡 [MQTT] Đã gửi cảnh báo {event.event_type} (Track #{event.track_id}) tới topic {topic_name}")
        else:
            logger.warning(f"⚠️ [MQTT] Chưa kết nối broker: cảnh báo {event.event_type} ({event.event_id}) đã lưu outbox, sẽ gửi khi kết nối lại.")
        return True

    def flush_once(self):
        """Gửi các cảnh báo tồn trong outbox (nếu đang kết nối) và dọn dòng quá hạn."""
        if self.client is not None and self.is_connected:
            with self._send_lock:
                while self.is_connected and not self._stop.is_set():
                    rows = self.outbox.due_rows(_FLUSH_BATCH_SIZE, settings.MQTT_OUTBOX_INFLIGHT_TIMEOUT_SECONDS)
                    for event_id, topic, payload in rows:
                        self._publish_row(event_id, topic, payload)
                    if rows:
                        logger.info(f"📤 [MQTT Outbox] Đã gửi lại {len(rows)} cảnh báo tồn")
                    if len(rows) < _FLUSH_BATCH_SIZE:
                        break
        self.outbox.purge(settings.MQTT_OUTBOX_MAX_ROWS, settings.MQTT_OUTBOX_MAX_AGE_HOURS)

    def _flush_loop(self):
        while not self._stop.is_set():
            self._wake.wait(settings.MQTT_OUTBOX_FLUSH_SECONDS)
            self._wake.clear()
            if self._stop.is_set():
                break
            try:
                self.flush_once()
            except Exception as e:
                logger.error(f"❌ [MQTT Outbox] Lỗi khi gửi lại cảnh báo tồn: {e}")

    def close(self):
        self._stop.set()
        self._wake.set()
        if self._flush_thread is not None:
            self._flush_thread.join(timeout=5)
        if self.client:
            try:
                self.client.loop_stop()
                self.client.disconnect()
            except Exception:
                pass


_shared_client: Optional[SecurityMqttClient] = None
_shared_client_lock = threading.Lock()


def get_security_mqtt_client() -> SecurityMqttClient:
    """Một MQTT client (một kết nối, một luồng flush) dùng chung cho mọi pipeline trong tiến trình."""
    global _shared_client
    if _shared_client is None:
        with _shared_client_lock:
            if _shared_client is None:
                _shared_client = SecurityMqttClient()
    return _shared_client
