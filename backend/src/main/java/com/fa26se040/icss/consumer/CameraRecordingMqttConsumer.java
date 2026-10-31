package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.camera.RecordingCompletedEventDto;
import com.fa26se040.icss.service.CameraRecordingService;
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
public class CameraRecordingMqttConsumer implements IMqttMessageListener {

    private final CameraRecordingService cameraRecordingService;
    private final ObjectMapper objectMapper;

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (message == null || message.getPayload() == null) {
            return;
        }
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        processMessage(payload);
    }

    public void processMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        String trimmed = payload.trim();
        if (!trimmed.startsWith("{")) {
            log.warn("⚠️ [Recording MQTT] Bỏ qua payload không phải JSON Object: {}", trimmed);
            return;
        }
        try {
            RecordingCompletedEventDto dto = objectMapper.readValue(trimmed, RecordingCompletedEventDto.class);
            if (dto == null) {
                return;
            }
            cameraRecordingService.ingestCompletedRecording(dto);
        } catch (Exception e) {
            log.error("❌ [Recording MQTT] Lỗi xử lý sự kiện hoàn tất ghi hình: {}", e.getMessage());
        }
    }
}
