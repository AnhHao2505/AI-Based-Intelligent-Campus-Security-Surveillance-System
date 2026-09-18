package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityIncidentKafkaConsumer {

    private final SecurityIncidentService securityIncidentService;
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
            securityIncidentService.ingestIncident(incident);
        } catch (Exception e) {
            log.error("❌ [Kafka Consumer] Lỗi xử lý sự kiện an ninh từ Kafka: {}", e.getMessage(), e);
        }
    }
}
