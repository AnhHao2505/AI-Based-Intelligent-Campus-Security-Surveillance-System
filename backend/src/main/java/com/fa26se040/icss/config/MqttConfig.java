package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.CameraRecordingMqttConsumer;
import com.fa26se040.icss.consumer.EdgeStatusMqttConsumer;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.service.EdgeMqttControlService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
// Có 2 constructor (constructor 3 tham số giữ cho test cũ) -> phải chỉ rõ constructor Spring dùng,
// nếu không Spring tìm constructor rỗng và backend không khởi động được ("No default constructor found").
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class MqttConfig {

    /** Tiền tố topic phản hồi RPC từ Edge (khớp EdgeMqttControlService.TOPIC_RPC_REPLY_WILDCARD). */
    static final String RPC_REPLY_TOPIC_PREFIX = "campus/edge/rpc/reply/";
    /** Topic và tiền tố topic theo dõi trạng thái heartbeat của Edge (M4). */
    public static final String TOPIC_EDGE_STATUS_WILDCARD = "campus/edge/status/+";
    public static final String EDGE_STATUS_TOPIC_PREFIX = "campus/edge/status/";

    private final SecurityIncidentMqttConsumer securityIncidentMqttConsumer;
    private final CameraRecordingMqttConsumer cameraRecordingMqttConsumer;
    private final EdgeMqttControlService edgeMqttControlService;
    private final EdgeStatusMqttConsumer edgeStatusMqttConsumer;

    public MqttConfig(SecurityIncidentMqttConsumer securityIncidentMqttConsumer,
                      CameraRecordingMqttConsumer cameraRecordingMqttConsumer,
                      EdgeMqttControlService edgeMqttControlService) {
        this(securityIncidentMqttConsumer, cameraRecordingMqttConsumer, edgeMqttControlService, null);
    }

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

    @Value("${app.mqtt.username:icss-backend}")
    private String username;

    @Value("${app.mqtt.password:}")
    private String password;

    @Value("${app.mqtt.connect-retry-seconds:10}")
    private long connectRetrySeconds;

    @Value("${app.mqtt.truststore-path:}")
    private String truststorePath;

    @Value("${app.mqtt.truststore-password:}")
    private String truststorePassword;

    @Value("${app.mqtt.truststore-type:JKS}")
    private String truststoreType;

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
            connectOptions = buildConnectOptions();

            mqttExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mqtt-connect");
                t.setDaemon(true);
                return t;
            });
            mqttClient.setCallback(new IncidentMqttCallback());
            edgeMqttControlService.bindMqttClient(mqttClient);

            log.info("🔌 [MQTT] Đang kết nối tới MQTT Broker: {} (Client ID: {}, user: {})",
                    brokerUrl, clientId, connectOptions.getUserName() != null ? connectOptions.getUserName() : "<ẩn danh>");
            connectOrScheduleRetry();
        } catch (Exception e) {
            log.warn("⚠️ [MQTT] Không thể khởi tạo MQTT Client ({}): {}", brokerUrl, e.getMessage());
        }
    }

    /**
     * Xây dựng MqttConnectOptions với hỗ trợ kết nối bảo mật qua SSL/TLS (M1).
     */
    MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        if (password != null && !password.isEmpty()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }

        // M1: Cấu hình SSL/TLS nếu brokerUrl dùng giao thức ssl://
        if (brokerUrl != null && brokerUrl.startsWith("ssl://")) {
            if (truststorePath != null && !truststorePath.isBlank()) {
                try {
                    options.setSocketFactory(createSocketFactory(truststorePath, truststorePassword, truststoreType));
                    log.info("🔒 [MQTT] Đã kích hoạt SSL/TLS với custom truststore: {} (type: {})", truststorePath, truststoreType);
                } catch (Exception e) {
                    log.error("❌ [MQTT] Không thể tải truststore cho kết nối SSL ({}): {}", truststorePath, e.getMessage());
                    throw new IllegalStateException("Lỗi cấu hình SSL MQTT truststore: " + e.getMessage(), e);
                }
            } else {
                log.info("🔒 [MQTT] Kết nối SSL/TLS sử dụng TrustManager mặc định của JVM");
            }
        }

        return options;
    }

    SSLSocketFactory createSocketFactory(String path, String pass, String type) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(type != null && !type.isBlank() ? type : "JKS");
        try (InputStream is = new FileInputStream(path)) {
            trustStore.load(is, pass != null && !pass.isEmpty() ? pass.toCharArray() : null);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        return sslContext.getSocketFactory();
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

    private void subscribeAllTopics() {
        subscribe(incidentsTopic, securityIncidentMqttConsumer);
        subscribe(recordingsTopic, cameraRecordingMqttConsumer);
        subscribe(EdgeMqttControlService.TOPIC_RPC_REPLY_WILDCARD, edgeMqttControlService::handleRpcReplyMessage);
        if (edgeStatusMqttConsumer != null) {
            subscribe(TOPIC_EDGE_STATUS_WILDCARD, edgeStatusMqttConsumer);
        }
    }

    private void subscribe(String topic, IMqttMessageListener listener) {
        try {
            mqttClient.subscribe(topic, 1, listener);
            log.info("📡 [MQTT] Đã đăng ký lắng nghe topic: {} (QoS 1)", topic);
        } catch (MqttException e) {
            log.warn("⚠️ [MQTT] Không thể đăng ký topic {}: {}", topic, e.getMessage());
        }
    }

    /**
     * Định tuyến tin tới callback chung (chưa có listener riêng): tin tồn của phiên cũ (cleanSession=false)
     * có thể tới trước khi subscribe() gắn listener; paho khi đó chuyển tin vào đây rồi tự PUBACK,
     * nên phải giao đúng consumer theo topic để không mất cảnh báo / metadata ghi hình / phản hồi RPC.
     */
    void routeUnlistenedMessage(String topic, MqttMessage message) {
        if (topic == null) {
            log.warn("⚠️ [MQTT] Bỏ tin không có topic");
            return;
        }
        if (topic.equals(incidentsTopic)) {
            securityIncidentMqttConsumer.messageArrived(topic, message);
        } else if (topic.equals(recordingsTopic)) {
            cameraRecordingMqttConsumer.messageArrived(topic, message);
        } else if (topic.startsWith(RPC_REPLY_TOPIC_PREFIX)) {
            edgeMqttControlService.handleRpcReplyMessage(topic, message);
        } else if (topic.startsWith(EDGE_STATUS_TOPIC_PREFIX)) {
            if (edgeStatusMqttConsumer != null) {
                edgeStatusMqttConsumer.messageArrived(topic, message);
            }
        } else {
            log.warn("⚠️ [MQTT] Bỏ tin từ topic không đăng ký: {}", topic);
        }
    }

    private class IncidentMqttCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            log.info("✅ [MQTT] Kết nối thành công tới MQTT Broker: {}{}", serverURI, reconnect ? " (kết nối lại)" : "");
            // Gắn client cho dịch vụ điều khiển Edge (chỉ gán tham chiếu, không gọi paho)
            edgeMqttControlService.bindMqttClient(mqttClient);
            // Không gọi subscribe() ngay trong callback của paho (dễ deadlock), đẩy sang executor
            if (mqttExecutor != null && !mqttExecutor.isShutdown()) {
                mqttExecutor.execute(MqttConfig.this::subscribeAllTopics);
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("⚠️ [MQTT] Mất kết nối tới MQTT Broker ({}): {}. Paho sẽ tự kết nối lại.",
                    brokerUrl, cause != null ? cause.getMessage() : "không rõ");
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            routeUnlistenedMessage(topic, message);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Lệnh/cấu hình gửi Edge (EdgeMqttControlService) không cần xử lý thêm khi broker xác nhận
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
