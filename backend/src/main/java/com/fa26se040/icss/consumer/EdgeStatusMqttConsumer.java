package com.fa26se040.icss.consumer;

import com.fa26se040.icss.service.EdgeStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeStatusMqttConsumer implements IMqttMessageListener {

    private final EdgeStatusService edgeStatusService;

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (message == null || message.getPayload() == null) {
            log.warn("⚠️ [EdgeStatusMqttConsumer] Nhận tin nhắn rỗng từ topic [{}]", topic);
            return;
        }

        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        edgeStatusService.processHeartbeat(topic, payload);
    }
}
