package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityIncidentMqttConsumer implements IMqttMessageListener {

    private final SecurityIncidentService securityIncidentService;
    private final ObjectMapper objectMapper;

    /**
     * Callback khi nhận được message từ MQTT broker
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (message == null || message.getPayload() == null) {
            log.warn("⚠️ [MQTT Consumer] Bỏ qua message null hoặc rỗng từ topic {}", topic);
            return;
        }
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.info("🔔 [MQTT Consumer] Nhận sự kiện từ topic [{}]: {}", topic, payload);
        processMessage(payload);
    }

    /**
     * Xử lý payload chuỗi JSON sự kiện an ninh từ AI Service
     */
    public void processMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("⚠️ [MQTT Consumer] Payload rỗng hoặc null, bỏ qua");
            return;
        }

        try {
            IncidentEventDto incident = objectMapper.readValue(payload, IncidentEventDto.class);
            if (incident == null) {
                log.warn("⚠️ [MQTT Consumer] Không thể parse IncidentEventDto từ payload");
                return;
            }
            securityIncidentService.ingestIncident(incident);
        } catch (Exception e) {
            log.error("❌ [MQTT Consumer] Lỗi xử lý sự kiện an ninh từ MQTT: {}", e.getMessage(), e);
        }
    }
}
