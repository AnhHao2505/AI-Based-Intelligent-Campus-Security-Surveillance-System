package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.EdgeDeviceStatus;
import com.fa26se040.icss.repository.EdgeDeviceStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EdgeStatusServiceTest {

    @Mock
    private EdgeDeviceStatusRepository repository;

    private EdgeStatusService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EdgeStatusService(repository, objectMapper);
    }

    @Test
    @DisplayName("M4: processHeartbeat lưu trạng thái ONLINE và danh sách camera từ payload")
    void processHeartbeat_OnlinePayload_Success() {
        String topic = "campus/edge/status/edge-cam-01";
        String payload = """
                {
                    "edge_id": "edge-cam-01",
                    "status": "ONLINE",
                    "ts": "2026-11-11T08:15:30.123Z",
                    "cameras": [
                        {"camera_code": "CAM-001", "fps": 25.0, "connected": true}
                    ],
                    "gpu": false,
                    "version": "1.0.0"
                }
                """;

        when(repository.findById("edge-cam-01")).thenReturn(Optional.empty());

        service.processHeartbeat(topic, payload);

        ArgumentCaptor<EdgeDeviceStatus> captor = ArgumentCaptor.forClass(EdgeDeviceStatus.class);
        verify(repository).save(captor.capture());

        EdgeDeviceStatus saved = captor.getValue();
        assertEquals("edge-cam-01", saved.getEdgeId());
        assertEquals("ONLINE", saved.getStatus());
        // last_seen theo giờ server lúc nhận (không theo ts của edge, tránh flapping khi đồng hồ edge lệch)
        assertTrue(Math.abs(java.time.Duration.between(OffsetDateTime.now(), saved.getLastSeen()).getSeconds()) < 5);
        assertFalse(saved.getGpu());
        assertEquals("1.0.0", saved.getVersion());
        assertTrue(saved.getCamerasJson().contains("CAM-001"));
    }

    @Test
    @DisplayName("M4: processHeartbeat cập nhật trạng thái OFFLINE (LWT) khi nhận tín hiệu ngắt kết nối")
    void processHeartbeat_OfflinePayload_UpdatesStatus() {
        String topic = "campus/edge/status/edge-cam-01";
        String payload = """
                {
                    "edge_id": "edge-cam-01",
                    "status": "OFFLINE",
                    "ts": "2026-11-11T08:20:00.000Z"
                }
                """;

        EdgeDeviceStatus existing = EdgeDeviceStatus.builder()
                .edgeId("edge-cam-01")
                .status("ONLINE")
                .lastSeen(OffsetDateTime.parse("2026-11-11T08:15:30.123Z"))
                .build();
        when(repository.findById("edge-cam-01")).thenReturn(Optional.of(existing));

        service.processHeartbeat(topic, payload);

        verify(repository).save(existing);
        assertEquals("OFFLINE", existing.getStatus());
        assertTrue(Math.abs(java.time.Duration.between(OffsetDateTime.now(), existing.getLastSeen()).getSeconds()) < 5);
    }

    @Test
    @DisplayName("M4: Đồng hồ edge lệch nhiều giờ vẫn được tính là vừa thấy (không bị checkOfflineEdges đánh OFFLINE)")
    void processHeartbeat_EdgeClockSkewed_UsesServerReceiveTime() {
        String payload = """
                {"edge_id": "edge-pi", "status": "ONLINE", "ts": "1970-01-01T00:00:10.000Z"}
                """;
        when(repository.findById("edge-pi")).thenReturn(Optional.empty());

        service.processHeartbeat("campus/edge/status/edge-pi", payload);

        ArgumentCaptor<EdgeDeviceStatus> captor = ArgumentCaptor.forClass(EdgeDeviceStatus.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getLastSeen().isAfter(OffsetDateTime.now().minusSeconds(90)));
    }

    @Test
    @DisplayName("M4: checkOfflineEdges đánh dấu OFFLINE các thiết bị quá 3 chu kỳ (90s) không gửi tin")
    void checkOfflineEdges_MarksStaleDevicesOffline() {
        EdgeDeviceStatus staleDevice = EdgeDeviceStatus.builder()
                .edgeId("edge-timeout-01")
                .status("ONLINE")
                .lastSeen(OffsetDateTime.now().minusSeconds(100))
                .build();

        when(repository.findByStatusAndLastSeenBefore(eq("ONLINE"), any(OffsetDateTime.class)))
                .thenReturn(List.of(staleDevice));

        service.checkOfflineEdges();

        assertEquals("OFFLINE", staleDevice.getStatus());
        verify(repository).save(staleDevice);
    }

    @Test
    @DisplayName("M4: processHeartbeat bỏ qua payload rỗng mà không báo lỗi")
    void processHeartbeat_EmptyPayload_Ignored() {
        service.processHeartbeat("campus/edge/status/cam-01", null);
        service.processHeartbeat("campus/edge/status/cam-01", "   ");

        verify(repository, never()).save(any());
    }
}
