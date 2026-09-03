package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityIncidentKafkaConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Lắng nghe các sự kiện vi phạm an ninh bắn ra từ AI Service qua Apache Kafka
     */
    @KafkaListener(
            topics = "${app.kafka.topics.incidents:security-incidents}",
            groupId = "${spring.kafka.consumer.group-id:campus-security-backend-group}"
    )
    public void consumeIncidentEvent(String message) {
        try {
            log.info("🔔 [Kafka Consumer] Nhận sự kiện an ninh từ AI Service: {}", message);

            IncidentEventDto incident = objectMapper.readValue(message, IncidentEventDto.class);

            // Chuẩn hóa đường dẫn ảnh MinIO cho trình duyệt Web nếu cần
            if (incident.getImageUrl() != null && incident.getImageUrl().contains("minio:9000")) {
                incident.setImageUrl(incident.getImageUrl().replace("minio:9000", "localhost:9000"));
            }

            // Đẩy tức thì qua WebSocket tới topic /topic/security-alerts
            messagingTemplate.convertAndSend("/topic/security-alerts", incident);
            log.info("🚀 [WebSocket Broadcast] Đã đẩy cảnh báo [{}] của camera [{}] tới Màn hình Bảo vệ!",
                    incident.getEventType(), incident.getCameraCode());

        } catch (Exception e) {
            log.error("❌ [Kafka Consumer] Lỗi xử lý sự kiện an ninh từ Kafka: {}", e.getMessage(), e);
        }
    }
}
