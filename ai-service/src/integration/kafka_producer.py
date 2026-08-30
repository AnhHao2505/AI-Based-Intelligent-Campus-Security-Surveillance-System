import json
import logging
from typing import Optional
from ..core.entity import SecurityAlertEvent
from ..config import settings

logger = logging.getLogger(__name__)

class SecurityKafkaProducer:
    """
    Module gửi sự kiện an ninh thời gian thực vào Kafka Broker.
    Hỗ trợ chế độ Safe Mode (tự động bỏ qua lỗi nếu chưa bật Kafka để không làm đứt gãy luồng xử lý video).
    """
    def __init__(self, bootstrap_servers: Optional[str] = None):
        self.bootstrap_servers = bootstrap_servers or settings.KAFKA_BOOTSTRAP_SERVERS
        self.producer = None
        self.is_connected = False
        self._init_producer()

    def _init_producer(self):
        if not settings.KAFKA_ENABLED:
            logger.info("Kafka integration đang ở trạng thái TẮT (KAFKA_ENABLED=False).")
            return

        try:
            from kafka import KafkaProducer
            self.producer = KafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                request_timeout_ms=3000,
                max_block_ms=3000,
                retries=2
            )
            self.is_connected = True
            logger.info(f"Kết nối Kafka thành công tới {self.bootstrap_servers}")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"Không thể kết nối tới Kafka ({self.bootstrap_servers}): {e}. Chạy ở chế độ Standalone / Mock.")

    def send_alert(self, event: SecurityAlertEvent, topic: Optional[str] = None) -> bool:
        """Gửi sự kiện cảnh báo an ninh lên Kafka topic"""
        topic_name = topic or settings.KAFKA_TOPIC_ALERTS
        event_dict = event.to_dict()

        if self.is_connected and self.producer:
            try:
                self.producer.send(topic_name, value=event_dict)
                self.producer.flush()
                logger.info(f"[KAFKA] Đã gửi cảnh báo {event.event_type} (Track #{event.track_id}) tới topic {topic_name}")
                return True
            except Exception as e:
                logger.error(f"[KAFKA LỖI] Gửi message thất bại: {e}")
                return False
        else:
            logger.info(f"[MOCK KAFKA] Cảnh báo phát sinh: {json.dumps(event_dict, ensure_ascii=False)}")
            return True

    def close(self):
        if self.producer:
            try:
                self.producer.close()
            except Exception:
                pass
