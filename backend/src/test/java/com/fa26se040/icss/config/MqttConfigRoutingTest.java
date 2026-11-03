package com.fa26se040.icss.config;

import com.fa26se040.icss.consumer.CameraRecordingMqttConsumer;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.service.EdgeMqttControlService;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tin tới callback chung (trước khi subscribe() gắn listener riêng, vd tin tồn của phiên cleanSession=false)
 * phải được giao đúng consumer theo topic; topic lạ bị bỏ.
 */
@ExtendWith(MockitoExtension.class)
class MqttConfigRoutingTest {

    private static final String INCIDENTS = "campus/alerts/incidents";
    private static final String RECORDINGS = "campus/recordings/completed";

    @Mock
    private SecurityIncidentMqttConsumer incidentConsumer;

    @Mock
    private CameraRecordingMqttConsumer recordingConsumer;

    @Mock
    private EdgeMqttControlService edgeMqttControlService;

    private MqttConfig config;
    private final MqttMessage message = new MqttMessage("{}".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        config = new MqttConfig(incidentConsumer, recordingConsumer, edgeMqttControlService);
        ReflectionTestUtils.setField(config, "incidentsTopic", INCIDENTS);
        ReflectionTestUtils.setField(config, "recordingsTopic", RECORDINGS);
    }

    @Test
    @DisplayName("Topic sự cố -> SecurityIncidentMqttConsumer")
    void incidentsTopic_goesToIncidentConsumer() throws Exception {
        config.routeUnlistenedMessage(INCIDENTS, message);

        verify(incidentConsumer).messageArrived(INCIDENTS, message);
        verifyNoInteractions(recordingConsumer, edgeMqttControlService);
    }

    @Test
    @DisplayName("Topic ghi hình -> CameraRecordingMqttConsumer")
    void recordingsTopic_goesToRecordingConsumer() throws Exception {
        config.routeUnlistenedMessage(RECORDINGS, message);

        verify(recordingConsumer).messageArrived(RECORDINGS, message);
        verifyNoInteractions(incidentConsumer, edgeMqttControlService);
    }

    @Test
    @DisplayName("Topic campus/edge/rpc/reply/<id> -> EdgeMqttControlService.handleRpcReplyMessage")
    void rpcReplyTopic_goesToEdgeControlService() throws Exception {
        String topic = "campus/edge/rpc/reply/corr-123";

        config.routeUnlistenedMessage(topic, message);

        verify(edgeMqttControlService).handleRpcReplyMessage(topic, message);
        verifyNoInteractions(incidentConsumer, recordingConsumer);
    }

    @Test
    @DisplayName("Topic lạ hoặc null -> bỏ, không consumer nào nhận")
    void unknownTopic_isDropped() throws Exception {
        config.routeUnlistenedMessage("campus/edge/config/system", message);
        config.routeUnlistenedMessage("campus/alerts/incidents/extra", message);
        config.routeUnlistenedMessage(null, message);

        verify(incidentConsumer, never()).messageArrived(anyString(), any());
        verify(recordingConsumer, never()).messageArrived(anyString(), any());
        verify(edgeMqttControlService, never()).handleRpcReplyMessage(anyString(), any());
    }
}
