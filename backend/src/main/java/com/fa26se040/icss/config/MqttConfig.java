package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqttConfig {

    private final SecurityIncidentMqttConsumer securityIncidentMqttConsumer;

    @Value("${app.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${app.mqtt.client-id:campus-security-backend}")
    private String clientId;

    @Value("${app.mqtt.topics.incidents:campus/alerts/incidents}")
    private String incidentsTopic;

    @Value("${app.mqtt.enabled:true}")
    private boolean mqttEnabled;

    private MqttClient mqttClient;

    @EventListener(ApplicationReadyEvent.class)
    public void initMqtt() {
        if (!mqttEnabled) {
            log.info("ℹ️ [MQTT] app.mqtt.enabled=false, bỏ qua kết nối MQTT Broker");
            return;
        }

        try {
            String uniqueClientId = clientId + "-" + System.currentTimeMillis();
            mqttClient = new MqttClient(brokerUrl, uniqueClientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            log.info("🔌 [MQTT] Đang kết nối tới MQTT Broker: {} (Client ID: {})", brokerUrl, uniqueClientId);
            mqttClient.connect(options);
            log.info("✅ [MQTT] Kết nối thành công tới MQTT Broker: {}", brokerUrl);

            mqttClient.subscribe(incidentsTopic, 1, securityIncidentMqttConsumer);
            log.info("📡 [MQTT] Đã đăng ký lắng nghe topic: {} (QoS 1)", incidentsTopic);
        } catch (Exception e) {
            log.warn("⚠️ [MQTT] Không thể kết nối tới MQTT Broker lúc khởi động ({}): {}. Hệ thống vẫn chạy, MQTT sẽ tự kết nối khi Broker online.",
                    brokerUrl, e.getMessage());
        }
    }

    @PreDestroy
    public void cleanUp() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                mqttClient.close();
                log.info("🛑 [MQTT] Đã ngắt kết nối MQTT Client an toàn");
            } catch (Exception e) {
                log.warn("Lỗi khi ngắt kết nối MQTT Client: {}", e.getMessage());
            }
        }
    }
}
