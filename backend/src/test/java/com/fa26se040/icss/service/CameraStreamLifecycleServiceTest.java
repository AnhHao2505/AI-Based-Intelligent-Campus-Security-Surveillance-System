package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamLifecycleResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraHealthLog;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.fa26se040.icss.config.AiServiceRestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraStreamLifecycleServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private CameraStreamConfigurationRepository streamConfigRepo;

    @Mock
    private CameraHealthLogRepository healthLogRepo;

    @Mock
    private CameraService cameraService;

    @Mock
    private MediaMtxService mediaMtxService;

    @Mock
    private AiServiceRestTemplate restTemplate;

    @Mock
    private EdgeMqttControlService edgeMqttControlService;

    @InjectMocks
    private CameraStreamLifecycleService cameraStreamLifecycleService;

    private UUID cameraId;
    private Camera camera;
    private CameraStreamConfiguration streamConfig;

    @BeforeEach
    void setUp() {
        cameraId = UUID.randomUUID();
        camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-001")
                .name("Camera Cổng Chính")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .build();

        streamConfig = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.50")
                .port(554)
                .mainStreamPath("/stream1")
                .build();

        ReflectionTestUtils.setField(cameraStreamLifecycleService, "aiServiceUrl", "http://ai-service:8000");
        ReflectionTestUtils.setField(cameraStreamLifecycleService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(cameraStreamLifecycleService, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("Phân tầng 1 (Valid Transition): startStream kích hoạt MediaMTX, AI Worker, chuyển ONLINE và ghi HealthLog")
    void startStream_Success_ShouldSyncMediaMtxStartAiSetOnlineAndLog() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://192.168.1.50:554/stream1");
        when(mediaMtxService.getGatewayRtspUrl("CAM-001")).thenReturn("rtsp://mediamtx:8554/cam-001");

        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.startStream(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(OperationalStatus.ONLINE, response.getOperationalStatus());
        assertEquals("CAM-001", response.getCameraCode());

        // Kiểm tra side-effects
        verify(mediaMtxService).syncCameraPath("CAM-001", "rtsp://192.168.1.50:554/stream1");
        verify(restTemplate).postForObject(eq("http://ai-service:8000/api/v1/stream/start"), any(Map.class), eq(Map.class));
        verify(cameraRepository).save(argThat(c -> c.getOperationalStatus() == OperationalStatus.ONLINE));

        ArgumentCaptor<CameraHealthLog> logCaptor = ArgumentCaptor.forClass(CameraHealthLog.class);
        verify(healthLogRepo).save(logCaptor.capture());
        assertEquals(OperationalStatus.ONLINE, logCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("Dual-Stream: startStream đồng bộ Sub Stream cho UI (CAM-001) và Main Stream cho AI Worker (CAM-001_main)")
    void startStream_DualStream_ShouldSyncBothStreamsAndRouteMainToAi() {
        CameraStreamConfiguration dualConfig = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.50")
                .port(554)
                .mainStreamPath("/cam/realmonitor?channel=1&subtype=0")
                .subStreamPath("/cam/realmonitor?channel=1&subtype=1")
                .build();

        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(dualConfig));
        when(cameraService.buildRtspUrl(dualConfig)).thenReturn("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=0");
        when(cameraService.buildSubRtspUrl(dualConfig)).thenReturn("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=1");
        when(mediaMtxService.getGatewayRtspUrl("CAM-001_main")).thenReturn("rtsp://mediamtx:8554/cam-001_main");

        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.startStream(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());

        // Kiểm tra đồng bộ 2 luồng: Luồng phụ gán vào CAM-001 cho WHEP UI, Luồng chính gán vào CAM-001_main
        verify(mediaMtxService).syncCameraPath("CAM-001", "rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=1");
        verify(mediaMtxService).syncCameraPath("CAM-001_main", "rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=0");

        // Kiểm tra AI Worker nhận luồng Main Stream
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("http://ai-service:8000/api/v1/stream/start"), bodyCaptor.capture(), eq(Map.class));
        assertEquals("rtsp://mediamtx:8554/cam-001_main", bodyCaptor.getValue().get("rtsp_url"));
        assertEquals("CAM-001", bodyCaptor.getValue().get("camera_code"));
    }

    @Test
    @DisplayName("Dual-Stream: Khi kết nối MQTT Edge, publish dual-stream config gồm sub stream và main stream qua MQTT")
    void startStream_DualStream_WhenMqttConnected_ShouldPublishDualStreamConfigViaMqtt() {
        CameraStreamConfiguration dualConfig = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.50")
                .port(554)
                .mainStreamPath("/cam/realmonitor?channel=1&subtype=0")
                .subStreamPath("/cam/realmonitor?channel=1&subtype=1")
                .build();

        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(dualConfig));
        when(cameraService.buildRtspUrl(dualConfig)).thenReturn("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=0");
        when(cameraService.buildSubRtspUrl(dualConfig)).thenReturn("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=1");
        when(edgeMqttControlService.isConnected()).thenReturn(true);

        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.startStream(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());

        verify(edgeMqttControlService).publishCameraStreamConfig(
                eq("CAM-001"),
                eq("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=1"),
                eq("rtsp://192.168.1.50:554/cam/realmonitor?channel=1&subtype=0"),
                eq(true),
                any()
        );
        // Không gọi mediaMtxService vì đã handled qua MQTT
        verify(mediaMtxService, never()).syncCameraPath(anyString(), anyString());
    }

    @Test
    @DisplayName("Phân tầng 1 (Valid Transition): stopStream xóa MediaMTX path, dừng AI Worker, chuyển OFFLINE và ghi HealthLog")
    void stopStream_Success_ShouldDeleteMediaMtxStopAiSetOfflineAndLog() {
        camera.setOperationalStatus(OperationalStatus.ONLINE);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.stopStream(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());

        verify(mediaMtxService).deleteCameraPath("CAM-001");
        verify(restTemplate).postForObject(contains("/api/v1/stream/stop?camera_code=CAM-001"), isNull(), eq(Map.class));
        verify(cameraRepository).save(argThat(c -> c.getOperationalStatus() == OperationalStatus.OFFLINE));

        ArgumentCaptor<CameraHealthLog> logCaptor = ArgumentCaptor.forClass(CameraHealthLog.class);
        verify(healthLogRepo).save(logCaptor.capture());
        assertEquals(OperationalStatus.OFFLINE, logCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("Phân tầng 2 (Idempotency): Gọi stopStream khi camera đã OFFLINE vẫn dọn dẹp và trả về thành công an toàn")
    void stopStream_AlreadyOffline_ShouldRemainIdempotent() {
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.stopStream(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());
    }

    @Test
    @DisplayName("Phân tầng 3 (Invalid Transition): Camera đã DECOMMISSIONED không được phép startStream")
    void startStream_DecommissionedCamera_ShouldThrowErrStream003() {
        camera.setStatus(CameraStatus.DECOMMISSIONED);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        CameraException ex = assertThrows(CameraException.class, () -> cameraStreamLifecycleService.startStream(cameraId));
        assertEquals(CameraErrorCode.ERR_STREAM_003, ex.getErrorCode());

        verify(mediaMtxService, never()).syncCameraPath(anyString(), anyString());
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }
}
