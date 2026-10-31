package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.CameraRecordingMqttConsumer;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.service.EdgeMqttControlService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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
    private final CameraRecordingMqttConsumer cameraRecordingMqttConsumer;
    private final EdgeMqttControlService edgeMqttControlService;

    @Value("${app.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${app.mqtt.client-id:campus-security-backend}")
    private String clientId;

    @Value("${app.mqtt.topics.incidents:campus/alerts/incidents}")
    private String incidentsTopic;

    @Value("${app.mqtt.topics.recordings:campus/recordings/completed}")
    private String recordingsTopic;

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

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    subscribeAllTopics();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("⚠️ [MQTT] Mất kết nối tới Broker: {}", cause != null ? cause.getMessage() : "unknown");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    // Handled by per-topic IMqttMessageListener subscriptions
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            log.info("🔌 [MQTT] Đang kết nối tới MQTT Broker: {} (Client ID: {})", brokerUrl, uniqueClientId);
            mqttClient.connect(options);
            edgeMqttControlService.bindMqttClient(mqttClient);
            log.info("✅ [MQTT] Kết nối thành công tới MQTT Broker: {}", brokerUrl);
        } catch (Exception e) {
            log.warn("⚠️ [MQTT] Không thể kết nối tới MQTT Broker lúc khởi động ({}): {}. Hệ thống vẫn chạy, MQTT sẽ tự kết nối khi Broker online.",
                    brokerUrl, e.getMessage());
        }
    }

    private void subscribeAllTopics() {
        try {
            if (mqttClient == null || !mqttClient.isConnected()) {
                return;
            }
            edgeMqttControlService.bindMqttClient(mqttClient);
            mqttClient.subscribe(incidentsTopic, 1, securityIncidentMqttConsumer);
            mqttClient.subscribe(recordingsTopic, 1, cameraRecordingMqttConsumer);
            mqttClient.subscribe(EdgeMqttControlService.TOPIC_RPC_REPLY_WILDCARD, 1, edgeMqttControlService::handleRpcReplyMessage);
            log.info("📡 [MQTT] Đã đăng ký lắng nghe topics: [{}], [{}], [{}]",
                    incidentsTopic, recordingsTopic, EdgeMqttControlService.TOPIC_RPC_REPLY_WILDCARD);
        } catch (Exception e) {
            log.warn("⚠️ [MQTT] Lỗi đăng ký topic sau khi kết nối: {}", e.getMessage());
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
