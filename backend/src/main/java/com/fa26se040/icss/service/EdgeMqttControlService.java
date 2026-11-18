package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.dto.edge.EdgeRpcRequestDto;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cloud-Edge Control Plane qua MQTT hai chiều (Outbound-Only từ Edge):
 * - Publish cấu hình bền vững (Retained=true, QoS=1) cho Camera Stream, ROI và System Config.
 * - Publish lệnh một chiều (Incident Stop, On-Demand Cloud Stream Relay).
 * - Thực thi RPC đồng bộ (Request / Reply theo correlationId) cho Test Connection, Snapshot và Face Embedding.
 */
@Slf4j
@Service
public class EdgeMqttControlService {

    public static final String ENC_ETM_PREFIX = "ENC_ETM:";
    private static final int ETM_NONCE_BYTES = 16;

    public static final String TOPIC_RPC_REQUEST = "campus/edge/rpc/request";
    public static final String TOPIC_RPC_REPLY_WILDCARD = "campus/edge/rpc/reply/+";
    public static final String TOPIC_CONFIG_CAMERAS_PREFIX = "campus/edge/config/cameras/";
    public static final String TOPIC_CONFIG_ROI_PREFIX = "campus/edge/config/roi/";
    public static final String TOPIC_CONFIG_SYSTEM = "campus/edge/config/system";
    public static final String TOPIC_CMD_INCIDENT_STOP = "campus/edge/cmd/incident-stop";
    public static final String TOPIC_CMD_CLOUD_RELAY = "campus/edge/cmd/cloud-relay";
    public static final String TOPIC_EVENTS_FACES = "campus/edge/events/faces";

    private final ObjectMapper objectMapper;
    private final String internalServiceKey;
    private final ConcurrentHashMap<String, CompletableFuture<EdgeRpcReplyDto>> pendingRequests = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile MqttClient mqttClient;

    @Autowired
    public EdgeMqttControlService(
            ObjectMapper objectMapper,
            @Value("${icss.internal.service-key:}") String internalServiceKey) {
        this.objectMapper = objectMapper;
        this.internalServiceKey = internalServiceKey != null ? internalServiceKey : "";
    }

    public EdgeMqttControlService(ObjectMapper objectMapper) {
        this(objectMapper, "");
    }

    public void bindMqttClient(MqttClient client) {
        this.mqttClient = client;
    }

    public boolean isConnected() {
        MqttClient client = this.mqttClient;
        return client != null && client.isConnected();
    }

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    public void handleRpcReplyMessage(String topic, MqttMessage message) {
        if (message == null || message.getPayload() == null) {
            return;
        }
        try {
            String raw = new String(message.getPayload(), StandardCharsets.UTF_8);
            EdgeRpcReplyDto reply = objectMapper.readValue(raw, EdgeRpcReplyDto.class);
            if (reply != null && reply.getCorrelationId() != null) {
                CompletableFuture<EdgeRpcReplyDto> future = pendingRequests.remove(reply.getCorrelationId());
                if (future != null) {
                    future.complete(reply);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Edge RPC] Không thể parse phản hồi từ topic {}: {}", topic, e.getMessage());
        }
    }

    public EdgeRpcReplyDto sendRpcRequest(
            String action,
            String cameraCode,
            Map<String, Object> payload,
            long timeoutMs
    ) {
        if (!isConnected()) {
            throw new CameraException(CameraErrorCode.ERR_EDGE_001);
        }

        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<EdgeRpcReplyDto> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);

        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : 5000L;

        try {
            Map<String, Object> safePayload = sanitizeAndEncryptRpcPayload(payload);
            EdgeRpcRequestDto reqDto = EdgeRpcRequestDto.builder()
                    .correlationId(correlationId)
                    .action(action)
                    .cameraCode(cameraCode)
                    .payload(safePayload)
                    .timestamp(System.currentTimeMillis())
                    .build();

            byte[] bytes = objectMapper.writeValueAsBytes(reqDto);
            MqttMessage mqttMessage = new MqttMessage(bytes);
            mqttMessage.setQos(1);
            mqttMessage.setRetained(false);

            mqttClient.publish(TOPIC_RPC_REQUEST, mqttMessage);
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("⏱️ [Edge RPC] Quá hạn chờ {}ms cho lệnh {} (camera={})", effectiveTimeout, action, cameraCode);
            throw new CameraException(CameraErrorCode.ERR_EDGE_002);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CameraException(CameraErrorCode.ERR_EDGE_001);
        } catch (ExecutionException ee) {
            throw new CameraException(CameraErrorCode.ERR_EDGE_001);
        } catch (CameraException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("❌ [Edge RPC] Lỗi gửi lệnh {} tới Edge: {}", action, e.getMessage());
            throw new CameraException(CameraErrorCode.ERR_EDGE_001);
        } finally {
            pendingRequests.remove(correlationId);
        }
    }

    public void publishCameraStreamConfig(
            String cameraCode,
            String rtspSourceUrl,
            boolean enabled,
            RoiGeometry roiGeometry
    ) {
        publishCameraStreamConfig(cameraCode, rtspSourceUrl, null, enabled, roiGeometry);
    }

    public void publishCameraStreamConfig(
            String cameraCode,
            String rtspSourceUrl,
            String mainRtspUrl,
            boolean enabled,
            RoiGeometry roiGeometry
    ) {
        if (cameraCode == null || cameraCode.isBlank()) {
            return;
        }
        String normCode = cameraCode.trim().toUpperCase();
        Map<String, Object> body = new HashMap<>();
        body.put("camera_code", normCode);
        body.put("rtsp_url", encryptMqttSecret(rtspSourceUrl != null ? rtspSourceUrl : ""));
        if (mainRtspUrl != null && !mainRtspUrl.isBlank()) {
            body.put("main_rtsp_url", encryptMqttSecret(mainRtspUrl));
        }
        body.put("enabled", enabled);
        if (roiGeometry != null) {
            body.put("roi_geometry", roiGeometry);
        }
        body.put("updated_at", OffsetDateTime.now().toString());
        publishInternal(TOPIC_CONFIG_CAMERAS_PREFIX + normCode, body, true);
    }

    public void publishRoiConfig(String cameraCode, Map<String, Object> roiPayload) {
        if (cameraCode == null || cameraCode.isBlank() || roiPayload == null) {
            return;
        }
        String normCode = cameraCode.trim().toUpperCase();
        publishInternal(TOPIC_CONFIG_ROI_PREFIX + normCode, roiPayload, true);
    }

    public void publishSystemConfig(Map<String, Object> systemConfig) {
        if (systemConfig == null || systemConfig.isEmpty()) {
            return;
        }
        publishInternal(TOPIC_CONFIG_SYSTEM, systemConfig, true);
    }

    public void publishIncidentStopCommand(String incidentId, boolean stopAll) {
        Map<String, Object> cmd = new HashMap<>();
        if (incidentId != null && !incidentId.isBlank()) {
            cmd.put("incident_id", incidentId);
        }
        cmd.put("stop_all", stopAll);
        cmd.put("timestamp", System.currentTimeMillis());
        publishInternal(TOPIC_CMD_INCIDENT_STOP, cmd, false);
    }

    public void publishCloudRelayCommand(String cameraCode, boolean active) {
        if (cameraCode == null || cameraCode.isBlank()) {
            return;
        }
        Map<String, Object> cmd = Map.of(
                "camera_code", cameraCode.trim().toUpperCase(),
                "active", active,
                "timestamp", System.currentTimeMillis()
        );
        publishInternal(TOPIC_CMD_CLOUD_RELAY, cmd, false);
    }

    public void publishFaceCacheEvent(String action, String userCode) {
        Map<String, Object> evt = Map.of(
                "action", action != null ? action : "REFRESH",
                "user_code", userCode != null ? userCode : "",
                "timestamp", System.currentTimeMillis()
        );
        publishInternal(TOPIC_EVENTS_FACES, evt, false);
    }

    private void publishInternal(String topic, Object body, boolean retained) {
        if (!isConnected()) {
            log.debug("ℹ️ [Edge MQTT] Broker chưa kết nối, bỏ qua publish topic {}", topic);
            return;
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(1);
            msg.setRetained(retained);
            mqttClient.publish(topic, msg);
            log.info("📡 [Edge MQTT] Đã publish {} (retained={})", topic, retained);
        } catch (Exception e) {
            log.warn("⚠️ [Edge MQTT] Không thể publish tới {}: {}", topic, e.getMessage());
        }
    }

    private Map<String, Object> sanitizeAndEncryptRpcPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new HashMap<>(payload);
        for (String sensitiveKey : java.util.List.of("rtsp_url", "main_rtsp_url", "password")) {
            Object val = copy.get(sensitiveKey);
            if (val instanceof String s && !s.isBlank()) {
                copy.put(sensitiveKey, encryptMqttSecret(s));
            }
        }
        return copy;
    }

    /**
     * F-02: Mã hóa xác thực (Encrypt-then-MAC: SHA-256 CTR + HMAC-SHA256) cho thông tin RTSP nhạy cảm
     * trước khi đẩy lên MQTT Broker (đặc biệt các bản tin Retained=true), dùng khóa dẫn xuất từ INTERNAL_SERVICE_KEY.
     */
    String encryptMqttSecret(String plaintext) {
        if (plaintext == null || plaintext.isBlank()
                || internalServiceKey == null || internalServiceKey.isBlank()
                || plaintext.startsWith(ENC_ETM_PREFIX)) {
            return plaintext;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] encKey = sha.digest(("mqtt-enc|" + internalServiceKey).getBytes(StandardCharsets.UTF_8));
            sha.reset();
            byte[] macKey = sha.digest(("mqtt-mac|" + internalServiceKey).getBytes(StandardCharsets.UTF_8));

            byte[] nonce = new byte[ETM_NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = new byte[plainBytes.length];
            int offset = 0;
            int counter = 0;
            while (offset < plainBytes.length) {
                sha.reset();
                sha.update(encKey);
                sha.update(nonce);
                sha.update(ByteBuffer.allocate(4).putInt(counter++).array());
                byte[] block = sha.digest();
                int chunk = Math.min(block.length, plainBytes.length - offset);
                for (int i = 0; i < chunk; i++) {
                    cipherBytes[offset + i] = (byte) (plainBytes[offset + i] ^ block[i]);
                }
                offset += chunk;
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            mac.update(nonce);
            mac.update(cipherBytes);
            byte[] tag = mac.doFinal();

            byte[] packed = new byte[nonce.length + cipherBytes.length + tag.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(cipherBytes, 0, packed, nonce.length, cipherBytes.length);
            System.arraycopy(tag, 0, packed, nonce.length + cipherBytes.length, tag.length);

            return ENC_ETM_PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi mã hóa thông tin RTSP cho MQTT", e);
        }
    }
}
