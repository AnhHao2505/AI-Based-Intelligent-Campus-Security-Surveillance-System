package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityIncidentMqttConsumerTest {

    @Mock
    private SecurityIncidentService securityIncidentService;

    private ObjectMapper objectMapper;
    private SecurityIncidentMqttConsumer mqttConsumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mqttConsumer = new SecurityIncidentMqttConsumer(securityIncidentService, objectMapper);
    }

    @Test
    @DisplayName("1. Happy Path: Process valid JSON payload from MQTT and forward to SecurityIncidentService")
    void consumeIncident_happyPath_success() {
        String validJson = """
            {
                "event_id": "EVT-TEST-001",
                "camera_code": "CAM_GATE_01",
                "event_type": "RESTRICTED_AREA_INTRUSION",
                "track_id": 105,
                "duration_seconds": 12.5,
                "confidence": 0.95,
                "image_url": "http://minio:9000/security-evidence/evidence1.jpg",
                "detected_at": "2026-10-31T10:00:00Z",
                "details": "Person detected in restricted zone"
            }
            """;

        assertThatCode(() -> mqttConsumer.processMessage(validJson))
                .doesNotThrowAnyException();

        ArgumentCaptor<IncidentEventDto> captor = ArgumentCaptor.forClass(IncidentEventDto.class);
        verify(securityIncidentService, times(1)).ingestIncident(captor.capture());

        IncidentEventDto captured = captor.getValue();
        assertThat(captured.getEventId()).isEqualTo("EVT-TEST-001");
        assertThat(captured.getCameraCode()).isEqualTo("CAM_GATE_01");
        assertThat(captured.getEventType()).isEqualTo("RESTRICTED_AREA_INTRUSION");
        assertThat(captured.getTrackId()).isEqualTo(105);
        assertThat(captured.getConfidence()).isEqualTo(0.95);
        assertThat(captured.getImageUrl()).isEqualTo("http://minio:9000/security-evidence/evidence1.jpg");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-json", "{broken_json:"})
    @DisplayName("2. Data Validation / Boundary: Malformed, null or empty payloads are handled gracefully")
    void consumeIncident_boundaryAndMalformedJson_handlesGracefullyWithoutException(String malformedPayload) {
        assertThatCode(() -> mqttConsumer.processMessage(malformedPayload))
                .doesNotThrowAnyException();

        verify(securityIncidentService, never()).ingestIncident(any());
    }

    @Test
    @DisplayName("3. Exception / Domain Error: Handles service exception without crashing MQTT consumer loop")
    void consumeIncident_domainServiceException_handlesGracefullyWithoutThrowing() {
        String validJson = """
            {
                "event_id": "EVT-ERR-999",
                "camera_code": "CAM_GATE_02",
                "event_type": "FIRE_DETECTED",
                "confidence": 0.88
            }
            """;

        doThrow(new RuntimeException("Database error or inactive camera"))
                .when(securityIncidentService).ingestIncident(any(IncidentEventDto.class));

        assertThatCode(() -> mqttConsumer.processMessage(validJson))
                .doesNotThrowAnyException();

        verify(securityIncidentService, times(1)).ingestIncident(any(IncidentEventDto.class));
    }
}
