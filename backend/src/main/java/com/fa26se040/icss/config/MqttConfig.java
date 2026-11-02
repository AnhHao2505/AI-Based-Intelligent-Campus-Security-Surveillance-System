package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    @Value("${app.mqtt.username:icss-backend}")
    private String username;

    @Value("${app.mqtt.password:}")
    private String password;

    @Value("${app.mqtt.connect-retry-seconds:10}")
    private long connectRetrySeconds;

    private MqttClient mqttClient;
    private MqttConnectOptions connectOptions;
    private ScheduledExecutorService mqttExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void initMqtt() {
        if (!mqttEnabled) {
            log.info("ℹ️ [MQTT] app.mqtt.enabled=false, bỏ qua kết nối MQTT Broker");
            return;
        }

        try {
            // Client ID cố định + cleanSession=false: broker giữ subscription và tin QoS 1 cho backend khi backend tắt
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(false);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            if (password != null && !password.isEmpty()) {
                options.setUserName(username);
                options.setPassword(password.toCharArray());
            }
            connectOptions = options;

            mqttExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mqtt-connect");
                t.setDaemon(true);
                return t;
            });
            mqttClient.setCallback(new IncidentMqttCallback());

            log.info("🔌 [MQTT] Đang kết nối tới MQTT Broker: {} (Client ID: {}, user: {})",
                    brokerUrl, clientId, options.getUserName() != null ? options.getUserName() : "<ẩn danh>");
            connectOrScheduleRetry();
        } catch (Exception e) {
            log.warn("⚠️ [MQTT] Không thể khởi tạo MQTT Client ({}): {}", brokerUrl, e.getMessage());
        }
    }

    /**
     * Paho v3 chỉ tự kết nối lại sau lần kết nối thành công đầu tiên,
     * nên lần đầu lỗi thì tự lên lịch thử lại cho tới khi kết nối được.
     */
    private void connectOrScheduleRetry() {
        if (mqttClient == null || mqttClient.isConnected()) {
            return;
        }
        try {
            mqttClient.connect(connectOptions);
            // Đăng ký topic nằm ở connectComplete(), chạy cả lần đầu lẫn khi tự kết nối lại
        } catch (MqttException e) {
            if (mqttExecutor == null || mqttExecutor.isShutdown()) {
                return;
            }
            log.warn("⚠️ [MQTT] Không thể kết nối tới MQTT Broker ({}): {}. Hệ thống vẫn chạy, thử kết nối lại sau {} giây.",
                    brokerUrl, e.getMessage(), connectRetrySeconds);
            mqttExecutor.schedule(this::connectOrScheduleRetry, connectRetrySeconds, TimeUnit.SECONDS);
        }
    }

    private void subscribeIncidents() {
        try {
            mqttClient.subscribe(incidentsTopic, 1, securityIncidentMqttConsumer);
            log.info("📡 [MQTT] Đã đăng ký lắng nghe topic: {} (QoS 1)", incidentsTopic);
        } catch (MqttException e) {
            log.warn("⚠️ [MQTT] Không thể đăng ký topic {}: {}", incidentsTopic, e.getMessage());
        }
    }

    private class IncidentMqttCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            log.info("✅ [MQTT] Kết nối thành công tới MQTT Broker: {}{}", serverURI, reconnect ? " (kết nối lại)" : "");
            // Không gọi subscribe() ngay trong callback của paho (dễ deadlock), đẩy sang executor
            if (mqttExecutor != null && !mqttExecutor.isShutdown()) {
                mqttExecutor.execute(MqttConfig.this::subscribeIncidents);
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("⚠️ [MQTT] Mất kết nối tới MQTT Broker ({}): {}. Paho sẽ tự kết nối lại.",
                    brokerUrl, cause != null ? cause.getMessage() : "không rõ");
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            // Tin tồn của phiên cũ (cleanSession=false) có thể tới trước khi subscribe() gắn listener;
            // paho khi đó chuyển tin vào đây rồi tự PUBACK, nên phải xử lý để không mất cảnh báo
            securityIncidentMqttConsumer.messageArrived(topic, message);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Backend chỉ subscribe, không publish
        }
    }

    @PreDestroy
    public void cleanUp() {
        if (mqttExecutor != null) {
            mqttExecutor.shutdownNow();
        }
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
