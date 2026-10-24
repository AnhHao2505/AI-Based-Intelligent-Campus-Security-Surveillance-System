package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraSnapshotResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraSnapshotServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private CameraStreamConfigurationRepository streamConfigRepo;

    @Mock
    private CameraHealthLogRepository healthLogRepo;

    @Mock
    private CameraService cameraService;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private CameraSnapshotService cameraSnapshotService;

    private UUID cameraId;
    private Camera camera;
    private CameraStreamConfiguration streamConfig;

    @BeforeEach
    void setUp() {
        cameraId = UUID.randomUUID();
        camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-01")
                .name("Camera Entrance")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .build();

        streamConfig = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.100")
                .port(554)
                .mainStreamPath("/live")
                .build();

        ReflectionTestUtils.setField(cameraSnapshotService, "aiServiceUrl", "http://ai-service:8000");
        ReflectionTestUtils.setField(cameraSnapshotService, "defaultTimeoutMs", 10000);
        ReflectionTestUtils.setField(cameraSnapshotService, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("Phân tầng 1 (Happy Path): captureSnapshot thành công - trả về Base64, kích thước, và TUYỆT ĐỐI KHÔNG lưu DB hay ghi health log")
    void captureSnapshot_Success_ShouldReturnBase64AndNeverMutateCameraOrHealthLog() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://admin:123456@192.168.1.100:554/live");

        Map<String, Object> aiResponse = Map.of(
                "success", true,
                "snapshot_base64", "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
                "width", 1920,
                "height", 1080,
                "latency_ms", 150
        );
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class))).thenReturn(aiResponse);

        CameraSnapshotResponse response = cameraSnapshotService.captureSnapshot(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", response.getSnapshotBase64());
        assertEquals(1920, response.getWidth());
        assertEquals(1080, response.getHeight());
        assertEquals(150L, response.getLatencyMs());

        // Kiểm tra tính Read-Only / Idempotency tuyệt đối: Không được lưu camera hay health log
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
        assertEquals(OperationalStatus.OFFLINE, camera.getOperationalStatus(), "Trạng thái camera không được thay đổi khi chỉ chụp snapshot");
    }

    @Test
    @DisplayName("Phân tầng 2 (Idempotency / Boundary): Gọi captureSnapshot 5 lần liên tiếp không sinh ra side-effects")
    void captureSnapshot_MultipleCalls_ShouldRemainIdempotentWithoutSideEffects() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://admin:123456@192.168.1.100:554/live");

        Map<String, Object> aiResponse = Map.of(
                "success", true,
                "snapshot_base64", "base64-frame",
                "width", 1280,
                "height", 720,
                "latency_ms", 80
        );
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class))).thenReturn(aiResponse);

        for (int i = 0; i < 5; i++) {
            CameraSnapshotResponse response = cameraSnapshotService.captureSnapshot(cameraId);
            assertTrue(response.isSuccess());
        }

        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 3 (Domain Error): Camera chưa có cấu hình stream -> Ném ERR_STREAM_001")
    void captureSnapshot_MissingStreamConfig_ShouldThrowErrStream001() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.empty());

        CameraException ex = assertThrows(CameraException.class, () -> cameraSnapshotService.captureSnapshot(cameraId));
        assertEquals(CameraErrorCode.ERR_STREAM_001, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 3 (Domain Error): Camera đã DECOMMISSIONED -> Ném ERR_STREAM_003")
    void captureSnapshot_DecommissionedCamera_ShouldThrowErrStream003() {
        camera.setStatus(CameraStatus.DECOMMISSIONED);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        CameraException ex = assertThrows(CameraException.class, () -> cameraSnapshotService.captureSnapshot(cameraId));
        assertEquals(CameraErrorCode.ERR_STREAM_003, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 3 (External Failure): AI-service báo lỗi trích xuất -> Ném ERR_SNAPSHOT_001 và không mutate DB")
    void captureSnapshot_AiServiceFailure_ShouldThrowErrSnapshot001() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://admin:123456@192.168.1.100:554/live");

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Snapshot failed"));

        CameraException ex = assertThrows(CameraException.class, () -> cameraSnapshotService.captureSnapshot(cameraId));
        assertEquals(CameraErrorCode.ERR_SNAPSHOT_001, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }
}
