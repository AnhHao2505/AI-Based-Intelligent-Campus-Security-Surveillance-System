package com.fa26se040.icss.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

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

    private static final String DUPLICATE_JSON = """
            {
                "event_id": "EVT-DUP-001",
                "camera_code": "CAM_GATE_01",
                "event_type": "UNAUTHORIZED_ENTRY"
            }
            """;

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SecurityIncidentMqttConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(SecurityIncidentMqttConsumer.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("4. Tin trùng event_id vấp unique index -> log INFO 'bỏ qua sự cố trùng' kèm event_id, không ném lỗi, không log ERROR")
    void consumeIncident_duplicateEventIdViolation_loggedAsInfoAndSkipped() {
        doThrow(new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: duplicate key value violates unique constraint \"uq_security_incidents_event_id\"", "23505")))
                .when(securityIncidentService).ingestIncident(any(IncidentEventDto.class));
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            assertThatCode(() -> mqttConsumer.processMessage(DUPLICATE_JSON)).doesNotThrowAnyException();

            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage()).contains("Bỏ qua sự cố trùng event_id").contains("EVT-DUP-001");
            });
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("5. Vi phạm ràng buộc khác -> vẫn log ERROR như nhánh catch chung, không ném lỗi")
    void consumeIncident_otherIntegrityViolation_stillLoggedAsError() {
        doThrow(new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: null value in column \"camera_code\" violates not-null constraint", "23502")))
                .when(securityIncidentService).ingestIncident(any(IncidentEventDto.class));
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            assertThatCode(() -> mqttConsumer.processMessage(DUPLICATE_JSON)).doesNotThrowAnyException();

            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("Lỗi xử lý sự kiện an ninh từ MQTT"));
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("Bỏ qua sự cố trùng"));
        } finally {
            detachLogAppender(appender);
        }
    }
}
