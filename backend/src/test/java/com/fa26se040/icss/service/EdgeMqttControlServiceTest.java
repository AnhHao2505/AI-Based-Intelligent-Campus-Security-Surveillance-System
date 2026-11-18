package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EdgeMqttControlServiceTest {

    @Mock
    private MqttClient mqttClient;

    private ObjectMapper objectMapper;
    private EdgeMqttControlService edgeMqttControlService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        edgeMqttControlService = new EdgeMqttControlService(objectMapper);
        edgeMqttControlService.bindMqttClient(mqttClient);
    }

    @Test
    @DisplayName("1. Happy Path (Main Workflow RPC): Gửi lệnh TEST_CONNECTION qua MQTT Outbound-Only và khớp phản hồi theo correlationId")
    void sendRpcRequest_happyPath_correlatesReplyAndCleansUpPendingMap() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            doAnswer(invocation -> {
                MqttMessage msg = invocation.getArgument(1);
                JsonNode reqJson = objectMapper.readTree(new String(msg.getPayload(), StandardCharsets.UTF_8));
                String correlationId = reqJson.get("correlationId").asText();

                executor.schedule(() -> {
                    String replyJson = """
                            {
                                "correlationId": "%s",
                                "success": true,
                                "latencyMs": 85,
                                "data": {
                                    "fps": 15.0
                                }
                            }
                            """.formatted(correlationId);
                    edgeMqttControlService.handleRpcReplyMessage(
                            "campus/edge/rpc/reply/" + correlationId,
                            new MqttMessage(replyJson.getBytes(StandardCharsets.UTF_8))
                    );
                }, 40, TimeUnit.MILLISECONDS);
                return null;
            }).when(mqttClient).publish(eq("campus/edge/rpc/request"), any(MqttMessage.class));

            EdgeRpcReplyDto reply = edgeMqttControlService.sendRpcRequest(
                    "TEST_CONNECTION",
                    "CAM-001",
                    Map.of("rtsp_url", "rtsp://192.168.1.100:554/live"),
                    2000L
            );

            assertNotNull(reply);
            assertTrue(reply.isSuccess());
            assertEquals(85L, reply.getLatencyMs());
            assertEquals(15.0, ((Number) reply.getData().get("fps")).doubleValue());
            assertEquals(0, edgeMqttControlService.getPendingRequestCount(), "Phải giải phóng hoàn toàn bộ nhớ pendingRequests");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("2. Data Validation & Retained Config: Publish cấu hình camera với cờ Retained=true, QoS=1 và bỏ qua mã camera rỗng")
    void publishCameraStreamConfig_setsRetainedTrueAndSkipsBlankCode() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);

        edgeMqttControlService.publishCameraStreamConfig("   ", "rtsp://192.168.1.10:554/live", true, null);
        verify(mqttClient, never()).publish(anyString(), any(MqttMessage.class));

        edgeMqttControlService.publishCameraStreamConfig("CAM-001", "rtsp://192.168.1.10:554/live", true, null);

        ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient, times(1)).publish(eq("campus/edge/config/cameras/CAM-001"), msgCaptor.capture());

        MqttMessage published = msgCaptor.getValue();
        assertEquals(1, published.getQos());
        assertTrue(published.isRetained(), "Bản tin cấu hình Edge bắt buộc phải có retained=true để Edge tự khôi phục sau khi rớt mạng");
    }

    @Test
    @DisplayName("3. Exception / Domain Error: Khi MQTT Broker mất kết nối, sendRpcRequest ném lỗi ERR_EDGE_001")
    void sendRpcRequest_whenBrokerDisconnected_throwsErrEdge001() {
        when(mqttClient.isConnected()).thenReturn(false);

        CameraException ex = assertThrows(CameraException.class, () ->
                edgeMqttControlService.sendRpcRequest("TEST_CONNECTION", "CAM-001", Map.of(), 1000L)
        );
        assertEquals(CameraErrorCode.ERR_EDGE_001, ex.getErrorCode());
    }

    @Test
    @DisplayName("4. NFR Timeout <= 5000ms: Khi Edge không phản hồi, hủy chờ đúng hạn cam kết, ném ERR_EDGE_002 và không rò rỉ bộ nhớ")
    void sendRpcRequest_whenEdgeTimesOut_enforcesTimeoutSlaAndCleansPendingMap() throws MqttException {
        when(mqttClient.isConnected()).thenReturn(true);
        doNothing().when(mqttClient).publish(eq("campus/edge/rpc/request"), any(MqttMessage.class));

        long startMs = System.currentTimeMillis();
        CameraException ex = assertThrows(CameraException.class, () ->
                edgeMqttControlService.sendRpcRequest("TEST_CONNECTION", "CAM-001", Map.of(), 250L)
        );
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertEquals(CameraErrorCode.ERR_EDGE_002, ex.getErrorCode());
        assertTrue(elapsedMs < 5000L, "Thời gian ngắt timeout phải tuân thủ NFR <= 5000ms (thực tế: " + elapsedMs + "ms)");
        assertEquals(0, edgeMqttControlService.getPendingRequestCount(), "Phải dọn sạch pendingRequests khi timeout");
    }

    @Test
    @DisplayName("5. F-02 Security: Khi có INTERNAL_SERVICE_KEY, publishCameraStreamConfig mã hóa ENC_ETM và không lộ mật khẩu RTSP lên MQTT Retained")
    void publishCameraStreamConfig_encryptsRtspUrlsWithInternalServiceKey() throws Exception {
        EdgeMqttControlService securedService = new EdgeMqttControlService(
                objectMapper,
                "0123456789abcdef0123456789abcdef"
        );
        securedService.bindMqttClient(mqttClient);
        when(mqttClient.isConnected()).thenReturn(true);

        securedService.publishCameraStreamConfig(
                "CAM-001",
                "rtsp://admin:SuperSecretPass@192.168.1.10:554/sub",
                "rtsp://admin:SuperSecretPass@192.168.1.10:554/main",
                true,
                null
        );

        ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient, times(1)).publish(eq("campus/edge/config/cameras/CAM-001"), msgCaptor.capture());

        String rawPayload = new String(msgCaptor.getValue().getPayload(), StandardCharsets.UTF_8);
        assertFalse(rawPayload.contains("SuperSecretPass"), "Mật khẩu RTSP không được xuất hiện dạng rõ trên MQTT");
        assertFalse(rawPayload.contains("rtsp://"), "URL RTSP không được xuất hiện dạng rõ trên MQTT");
        JsonNode json = objectMapper.readTree(rawPayload);
        assertTrue(json.get("rtsp_url").asText().startsWith(EdgeMqttControlService.ENC_ETM_PREFIX));
        assertTrue(json.get("main_rtsp_url").asText().startsWith(EdgeMqttControlService.ENC_ETM_PREFIX));
    }
}
