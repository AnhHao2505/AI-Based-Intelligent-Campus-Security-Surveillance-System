package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.camera.CameraSnapshotResponse;
import com.fa26se040.icss.dto.camera.CameraStreamLifecycleResponse;
import com.fa26se040.icss.dto.camera.TestConnectionResponse;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.util.RtspHandshakeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudEdgeServiceRoutingTest {

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
    private EdgeMqttControlService edgeMqttControlService;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private RtspHandshakeChecker rtspHandshakeChecker;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private FaceDataRepository faceDataRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityIncidentRepository incidentRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private CameraAccessLogService cameraAccessLogService;
    @Mock
    private GuardShiftRepository guardShiftRepository;
    @Mock
    private IncidentRecipientResolver incidentRecipientResolver;
    @Mock
    private IncidentEscalationRepository incidentEscalationRepository;
    @Mock
    private SystemConfigurationRepository systemConfigRepository;
    @Mock
    private SystemConfigurationChangeLogRepository systemConfigChangeLogRepository;
    @Mock
    private ObjectProvider<AreaService> areaServiceProvider;
    @Mock
    private ObjectProvider<InAppNotificationService> notificationServiceProvider;

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
                .host("192.168.1.105")
                .port(554)
                .mainStreamPath("/cam/realmonitor")
                .build();
    }

    @Test
    @DisplayName("1. Happy Path (Cloud-Edge Stream Lifecycle): Khi MQTT kết nối và không cấu hình local aiServiceUrl, startStream chỉ dùng MQTT Retained Config (0 gọi HTTP vào Edge)")
    void startStream_inCloudEdgeMode_publishesMqttConfigWithoutHttpCall() {
        CameraStreamLifecycleService lifecycleService = new CameraStreamLifecycleService(
                cameraRepository, streamConfigRepo, healthLogRepo, cameraService, mediaMtxService
        );
        ReflectionTestUtils.setField(lifecycleService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(lifecycleService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(lifecycleService, "restTemplate", restTemplate);

        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://admin:pass@192.168.1.105:554/cam/realmonitor");

        CameraStreamLifecycleResponse res = lifecycleService.startStream(cameraId);

        assertTrue(res.isSuccess());
        assertEquals(OperationalStatus.ONLINE, res.getOperationalStatus());
        verify(edgeMqttControlService, times(1)).publishCameraStreamConfig(
                eq("CAM-001"),
                eq("rtsp://admin:pass@192.168.1.105:554/cam/realmonitor"),
                eq(true),
                any()
        );
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("2. Happy Path (Cloud-Edge Snapshot via MQTT RPC + MinIO): Trích xuất snapshot qua NAT bằng MQTT RPC và tải ảnh từ MinIO")
    void captureSnapshot_inCloudEdgeMode_usesMqttRpcAndMinio() throws Exception {
        CameraSnapshotService snapshotService = new CameraSnapshotService(
                cameraRepository, streamConfigRepo, cameraService
        );
        ReflectionTestUtils.setField(snapshotService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(snapshotService, "minioStorageService", minioStorageService);
        ReflectionTestUtils.setField(snapshotService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(snapshotService, "defaultTimeoutMs", 5000);
        ReflectionTestUtils.setField(snapshotService, "restTemplate", restTemplate);

        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://admin:pass@192.168.1.105:554/cam/realmonitor");

        EdgeRpcReplyDto reply = EdgeRpcReplyDto.builder()
                .correlationId("corr-1")
                .success(true)
                .latencyMs(120L)
                .data(Map.of(
                        "bucket", "camera-snapshots",
                        "object_key", "CAM-001/snap.jpg",
                        "width", 854,
                        "height", 480
                ))
                .build();
        when(edgeMqttControlService.sendRpcRequest(eq("CAPTURE_SNAPSHOT"), eq("CAM-001"), anyMap(), anyLong()))
                .thenReturn(reply);
        when(minioStorageService.getObject("camera-snapshots", "CAM-001/snap.jpg"))
                .thenReturn(new ByteArrayInputStream("fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8)));

        CameraSnapshotResponse response = snapshotService.captureSnapshot(cameraId);

        assertTrue(response.isSuccess());
        assertTrue(response.getSnapshotBase64().startsWith("data:image/jpeg;base64,"));
        assertEquals(854, response.getWidth());
        assertEquals(480, response.getHeight());
        assertEquals(120L, response.getLatencyMs());
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("3. Boundary / Domain Error (Cloud-Edge RTSP Test): Khi camera LAN từ chối kết nối tại Edge, trả đúng mã lỗi từ Edge mà không gọi socket trực tiếp từ Cloud")
    void testConnection_inCloudEdgeMode_delegatesToEdgeAgentOverMqttRpc() {
        CameraConnectionService connService = new CameraConnectionService(
                cameraRepository, streamConfigRepo, rtspHandshakeChecker
        );
        ReflectionTestUtils.setField(connService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(connService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(connService, "defaultTimeoutMs", 5000);

        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));

        EdgeRpcReplyDto reply = EdgeRpcReplyDto.builder()
                .correlationId("corr-2")
                .success(false)
                .errorCode("ERR_CONN_TIMEOUT")
                .errorMessage("Timeout kết nối camera nội bộ 192.168.1.105")
                .build();
        when(edgeMqttControlService.sendRpcRequest(eq("TEST_CONNECTION"), eq("CAM-001"), anyMap(), anyLong()))
                .thenReturn(reply);

        TestConnectionResponse res = connService.testConnection(cameraId);

        assertFalse(res.isSuccess());
        assertEquals(OperationalStatus.OFFLINE, res.getOperationalStatus());
        assertEquals("ERR_CONN_TIMEOUT", res.getErrorCode());
        verifyNoInteractions(rtspHandshakeChecker);
    }

    @Test
    @DisplayName("4. Exception Error: Khi Edge trả về lỗi trích xuất snapshot qua MQTT RPC, ném đúng CameraException(ERR_SNAPSHOT_001)")
    void captureSnapshot_whenEdgeReturnsFailure_throwsErrSnapshot001() {
        CameraSnapshotService snapshotService = new CameraSnapshotService(
                cameraRepository, streamConfigRepo, cameraService
        );
        ReflectionTestUtils.setField(snapshotService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(snapshotService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(snapshotService, "defaultTimeoutMs", 5000);

        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));
        when(cameraService.buildRtspUrl(streamConfig)).thenReturn("rtsp://192.168.1.105:554/live");

        EdgeRpcReplyDto failedReply = EdgeRpcReplyDto.builder()
                .correlationId("corr-3")
                .success(false)
                .errorMessage("Cannot open RTSP stream")
                .build();
        when(edgeMqttControlService.sendRpcRequest(eq("CAPTURE_SNAPSHOT"), eq("CAM-001"), anyMap(), anyLong()))
                .thenReturn(failedReply);

        CameraException ex = assertThrows(CameraException.class, () -> snapshotService.captureSnapshot(cameraId));
        assertEquals(CameraErrorCode.ERR_SNAPSHOT_001, ex.getErrorCode());
    }

    @Test
    @DisplayName("5. Happy Path (Cloud-Edge Face Registration via MinIO Staging + MQTT RPC): Đăng ký khuôn mặt qua NAT không cần gọi HTTP vào Edge và tự dọn staging object")
    void registerFace_inCloudEdgeMode_usesMinioStagingAndMqttRpc() {
        FaceDataService faceDataService = new FaceDataService(faceDataRepository, userRepository, minioStorageService);
        ReflectionTestUtils.setField(faceDataService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(faceDataService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(faceDataService, "restTemplate", restTemplate);

        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(minioStorageService.getFacesBucket()).thenReturn("face-profiles");
        when(minioStorageService.uploadFaceImage(any(byte[].class), eq("NV001")))
                .thenReturn("http://minio:9000/face-profiles/NV001/NV001_front.jpg");

        EdgeRpcReplyDto rpcReply = EdgeRpcReplyDto.builder()
                .correlationId("corr-face-1")
                .success(true)
                .data(Map.of(
                        "face_count", 1,
                        "embedding_front", List.of(0.12f, -0.34f, 0.56f)
                ))
                .build();
        when(edgeMqttControlService.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenReturn(rpcReply);
        when(faceDataRepository.findByCode("NV001")).thenReturn(Optional.empty());
        when(userRepository.findByUserCodeAndDeletedAtIsNull("NV001")).thenReturn(Optional.empty());
        when(faceDataRepository.save(any(FaceData.class))).thenAnswer(inv -> {
            FaceData fd = inv.getArgument(0);
            fd.setId(UUID.randomUUID());
            return fd;
        });

        MockMultipartFile file = new MockMultipartFile(
                "frontImage", "front.jpg", "image/jpeg", "fake-jpg-content".getBytes(StandardCharsets.UTF_8)
        );

        FaceDataResponseDto result = faceDataService.registerFace("NV001", file);

        assertNotNull(result);
        assertEquals("NV001", result.getCode());
        verify(minioStorageService, times(1)).uploadStagingObject(eq("face-profiles"), startsWith("staging/"), any(byte[].class), eq("image/jpeg"));
        verify(minioStorageService, times(1)).removeObjectSafely(eq("face-profiles"), startsWith("staging/"));
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    @DisplayName("6. Happy Path (Cloud-Edge Incident Stop via MQTT Command): Khi bảo vệ xác nhận sự cố, gửi lệnh dừng ghi hình qua MQTT kèm eventId ánh xạ từ DB")
    void triggerIncidentRecordingStop_inCloudEdgeMode_publishesMqttCommandWithEventId() {
        SecurityIncidentService incidentService = new SecurityIncidentService(
                incidentRepository,
                cameraRepository,
                userRepository,
                messagingTemplate,
                cameraAccessLogService,
                guardShiftRepository,
                incidentRecipientResolver,
                incidentEscalationRepository
        );
        ReflectionTestUtils.setField(incidentService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(incidentService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(incidentService, "restTemplate", restTemplate);

        UUID dbIncidentId = UUID.randomUUID();
        SecurityIncident dbIncident = SecurityIncident.builder()
                .id(dbIncidentId)
                .eventId("evt-edge-999")
                .cameraCode("CAM-001")
                .build();
        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(incidentRepository.findById(dbIncidentId)).thenReturn(Optional.of(dbIncident));

        incidentService.triggerIncidentRecordingStop(dbIncidentId.toString());

        verify(edgeMqttControlService, times(1)).publishIncidentStopCommand(eq("evt-edge-999"), eq(false));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("7. Happy Path (Cloud-Edge System Config Sync): Đồng bộ cấu hình AI Runtime qua MQTT Retained Topic khi khởi động trên Cloud")
    void onApplicationReady_inCloudEdgeMode_publishesSystemConfigOverMqtt() {
        SystemConfigService configService = new SystemConfigService(
                systemConfigRepository,
                systemConfigChangeLogRepository,
                userRepository,
                areaServiceProvider,
                notificationServiceProvider
        );
        ReflectionTestUtils.setField(configService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(configService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(configService, "aiRestTemplate", restTemplate);

        when(edgeMqttControlService.isConnected()).thenReturn(true);

        configService.onApplicationReady();

        verify(edgeMqttControlService, atLeastOnce()).publishSystemConfig(anyMap());
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }
}
