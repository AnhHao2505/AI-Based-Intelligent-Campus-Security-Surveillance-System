package com.fa26se040.icss.consumer;

import com.fa26se040.icss.dto.camera.RecordingCompletedEventDto;
import com.fa26se040.icss.service.CameraRecordingService;
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
class CameraRecordingMqttConsumerTest {

    @Mock
    private CameraRecordingService cameraRecordingService;

    private CameraRecordingMqttConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CameraRecordingMqttConsumer(cameraRecordingService, new ObjectMapper());
    }

    @Test
    @DisplayName("1. Happy Path: Parse bản tin MQTT campus/recordings/completed và chuyển tiếp sang CameraRecordingService")
    void processMessage_happyPath_forwardsToRecordingService() {
        String json = """
                {
                    "camera_code": "CAM-001",
                    "incident_id": "550e8400-e29b-41d4-a716-446655440000",
                    "recording_type": "INCIDENT_EVIDENCE",
                    "storage_location": "CLOUD_MINIO",
                    "storage_bucket": "security-evidence",
                    "storage_key": "CAM-001/2026/10/31/Incident 31-10-2026 - 16-00-00.mp4",
                    "filename": "Incident 31-10-2026 - 16-00-00.mp4",
                    "start_time": "2026-10-31T16:00:00+07:00",
                    "end_time": "2026-10-31T16:00:18+07:00",
                    "duration_seconds": 18,
                    "frame_count": 270,
                    "fps": 15.0,
                    "resolution": "854x480",
                    "file_size_bytes": 1843200,
                    "checksum_sha256": "abc123def456"
                }
                """;

        assertThatCode(() -> consumer.processMessage(json)).doesNotThrowAnyException();

        ArgumentCaptor<RecordingCompletedEventDto> captor = ArgumentCaptor.forClass(RecordingCompletedEventDto.class);
        verify(cameraRecordingService, times(1)).ingestCompletedRecording(captor.capture());

        RecordingCompletedEventDto captured = captor.getValue();
        assertThat(captured.getCameraCode()).isEqualTo("CAM-001");
        assertThat(captured.getDurationSeconds()).isEqualTo(18);
        assertThat(captured.getStorageBucket()).isEqualTo("security-evidence");
        assertThat(captured.getChecksumSha256()).isEqualTo("abc123def456");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{invalid_json", "[]"})
    @DisplayName("2. Data Validation / Boundary: Bỏ qua an toàn các payload rỗng hoặc sai cú pháp JSON")
    void processMessage_malformedOrEmptyPayload_doesNotThrow(String rawPayload) {
        assertThatCode(() -> consumer.processMessage(rawPayload)).doesNotThrowAnyException();
        verify(cameraRecordingService, never()).ingestCompletedRecording(any());
    }

    @Test
    @DisplayName("3. Exception Resilience: Ngoại lệ từ tầng Service không làm ngắt kết nối luồng lắng nghe MQTT")
    void processMessage_whenServiceThrows_catchesAndLogsSafely() {
        String json = """
                {
                    "camera_code": "CAM-002",
                    "storage_key": "CAM-002/seg.mp4",
                    "filename": "seg.mp4",
                    "duration_seconds": 60,
                    "file_size_bytes": 4096
                }
                """;
        doThrow(new RuntimeException("DB temporary outage"))
                .when(cameraRecordingService).ingestCompletedRecording(any());

        assertThatCode(() -> consumer.processMessage(json)).doesNotThrowAnyException();
        verify(cameraRecordingService, times(1)).ingestCompletedRecording(any());
    }
}
