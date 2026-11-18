package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.CameraStreamConfigResponse;
import com.fa26se040.icss.dto.camera.CreateCameraRequest;
import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.dto.camera.RoiUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private CameraStreamConfigurationRepository cameraStreamConfigurationRepository;

    @Mock
    private MediaMtxService mediaMtxService;

    @Mock
    private AesEncryptionUtil aesEncryptionUtil;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private RoiGeometryValidator roiGeometryValidator;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private com.fa26se040.icss.util.RtspHandshakeChecker rtspHandshakeChecker;
    @Mock
    private com.fa26se040.icss.config.AiServiceRestTemplate restTemplate;

    @InjectMocks
    private CameraService cameraService;

    private Camera testCamera;
    private UUID testCameraId;

    @BeforeEach
    void setUp() {
        testCameraId = UUID.randomUUID();
        testCamera = Camera.builder()
                .id(testCameraId)
                .cameraCode("CAM-001")
                .name("Camera Cổng Chính")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(Area.builder().id(UUID.randomUUID()).name("Cổng chính").build())
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "defaultTimeoutMs", 10000);
    }

    @Test
    @DisplayName("CreateCamera: should auto-generate camera code using sequence (CAM-%03d)")
    void testCreateCameraAutoCodeFromSequence() {
        UUID areaId = UUID.randomUUID();
        when(areaRepository.findByIdAndDeletedAtIsNull(areaId))
                .thenReturn(Optional.of(Area.builder().id(areaId).isActive(true).build()));
        when(cameraRepository.getNextCameraCodeSequence()).thenReturn(5L);
        when(cameraRepository.save(any(Camera.class))).thenAnswer(invocation -> {
            Camera c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CreateCameraRequest req = CreateCameraRequest.builder()
                .name("Camera Tòa Nhà Alpha")
                .areaId(areaId)
                .build();

        CameraDetailResponse response = cameraService.createCamera(req);

        assertNotNull(response);
        assertEquals("CAM-005", response.getCameraCode());
        assertEquals("Camera Tòa Nhà Alpha", response.getName());
        assertEquals(CameraStatus.ACTIVE, response.getStatus());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());
        verify(cameraRepository).getNextCameraCodeSequence();
    }

    @Test
    @DisplayName("CreateCamera: should throw ERR_CAM_003 when cameraCode already exists")
    void testCreateCameraDuplicateCode() {
        UUID areaId = UUID.randomUUID();
        when(cameraRepository.existsByCameraCode("CAM-999")).thenReturn(true);

        CreateCameraRequest req = CreateCameraRequest.builder()
                .cameraCode("CAM-999")
                .name("Camera Trùng")
                .areaId(areaId)
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.createCamera(req));
        assertEquals(CameraErrorCode.ERR_CAM_003, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("ListCameras: forceSync=true should query MediaMTX live status")
    void testListCamerasWithForceSync() {
        Pageable effectivePageable = PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "cameraCode"));
        Page<Camera> cameraPage = new PageImpl<>(List.of(testCamera));
        when(cameraRepository.findFiltered(eq("%%"), isNull(), isNull(), eq(effectivePageable))).thenReturn(cameraPage);
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Collections.emptyMap());

        cameraService.listCameras(null, null, null, true, PageRequest.of(0, 10));

        verify(mediaMtxService).getLivePathStatuses();
    }

    @Test
    @DisplayName("UpsertStreamConfig: Pure persistence saves DB without MediaMTX side-effects and returns encrypted config")
    void testUpsertStreamConfigSuccess() {
        testCamera.setOperationalStatus(OperationalStatus.OFFLINE);
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        when(cameraStreamConfigurationRepository.findByCameraId(testCameraId)).thenReturn(Optional.empty());
        when(mediaMtxService.formatPathName("CAM-001")).thenReturn("cam-001");
        when(aesEncryptionUtil.encrypt("Secret123")).thenReturn("ENC_GCM:encrypted-blob");

        when(cameraStreamConfigurationRepository.save(any(CameraStreamConfiguration.class)))
                .thenAnswer(invocation -> {
                    CameraStreamConfiguration cfg = invocation.getArgument(0);
                    cfg.setId(UUID.randomUUID());
                    return cfg;
                });

        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host("192.168.1.50")
                .port(554)
                .username("admin")
                .password("Secret123")
                .mainStreamPath("/live/ch0")
                .build();

        CameraStreamConfigResponse response = cameraService.upsertStreamConfig(testCameraId, req);

        assertNotNull(response);
        assertEquals("192.168.1.50", response.getHost());
        assertEquals(554, response.getPort());
        assertEquals("admin", response.getUsername());
        assertTrue(response.getIsPasswordConfigured());
        assertEquals("http://localhost:8889/cam-001/whep", response.getWhepUrl());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());

        // Verify Gateway và Socket RTSP KHÔNG bị gọi khi chỉ Lưu cấu hình
        verify(mediaMtxService, never()).syncCameraPathStrict(any(), any());
        verify(mediaMtxService, never()).syncCameraPath(any(), any());
        verify(rtspHandshakeChecker, never()).check(any(), anyInt(), any(), anyInt());

        verify(aesEncryptionUtil).encrypt("Secret123");
        verify(cameraStreamConfigurationRepository).save(any());
        verify(cameraRepository).save(any());
    }

    @Test
    @DisplayName("UpsertStreamConfig: Throws ERR_STREAM_004 when camera is ONLINE to prevent hot-reload stream breakage")
    void testUpsertStreamConfig_rejectedWhenCameraIsOnline() {
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host("192.168.1.50")
                .port(554)
                .username("admin")
                .password("Secret123")
                .mainStreamPath("/live/ch0")
                .build();

        CameraException ex = assertThrows(CameraException.class, () ->
                cameraService.upsertStreamConfig(testCameraId, req));
        assertEquals(CameraErrorCode.ERR_STREAM_004, ex.getErrorCode());

        verify(cameraStreamConfigurationRepository, never()).save(any());
        verify(mediaMtxService, never()).syncCameraPath(any(), any());
    }

    @Test
    @DisplayName("DeleteCamera: happy path should soft-delete, keep the original area, and clean MediaMTX path")
    void testDeleteCamera_happyPath_keepsAreaAndCleansGateway() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        Area originalArea = testCamera.getArea();

        cameraService.deleteCamera(testCameraId);

        verify(cameraRepository).save(argThat(c ->
                c.getArea() == originalArea &&
                c.getStatus() == CameraStatus.DECOMMISSIONED &&
                c.getOperationalStatus() == OperationalStatus.OFFLINE &&
                c.getDeletedAt() != null
        ));
        verify(mediaMtxService).deleteCameraPath("CAM-001");
    }

    @Test
    @DisplayName("DecommissionCamera: should transition to DECOMMISSIONED and OFFLINE, retain area, and clean MediaMTX path")
    void testDecommissionCamera_happyPath_retainsAreaAndCleansGateway() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CameraDetailResponse response = cameraService.decommissionCamera(testCameraId);

        assertNotNull(response);
        assertEquals(CameraStatus.DECOMMISSIONED, response.getStatus());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());
        assertNotNull(testCamera.getArea(), "Decommission camera must retain area association (BR-CAM-06)");
        verify(cameraRepository).save(argThat(c ->
                c.getArea() != null &&
                c.getStatus() == CameraStatus.DECOMMISSIONED &&
                c.getOperationalStatus() == OperationalStatus.OFFLINE
        ));
        verify(mediaMtxService).deleteCameraPath("CAM-001");
    }

    @Test
    @DisplayName("DeleteCamera: not found should throw ERR_CAM_002")
    void testDeleteCamera_notFound_throwsERR_CAM_002() {
        UUID nonExistentId = UUID.randomUUID();
        when(cameraRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.deleteCamera(nonExistentId));
        assertEquals(CameraErrorCode.ERR_CAM_002, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
        verify(mediaMtxService, never()).deleteCameraPath(anyString());
    }

    @Test
    @DisplayName("ROI (Hạng mục 3): updateRoiGeometry lưu polygon hợp lệ khi snapshotBase64 == null, không gọi MinIO upload")
    void testUpdateRoiGeometry_withoutSnapshot_savesPolygonsWithoutMinio() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Khu vực cấm")
                .vertices(List.of(
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(0.1)).y(java.math.BigDecimal.valueOf(0.1)).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(0.9)).y(java.math.BigDecimal.valueOf(0.1)).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(0.9)).y(java.math.BigDecimal.valueOf(0.9)).build()
                ))
                .build();
        RoiUpdateRequest req = new RoiUpdateRequest();
        req.setRoiGeometry(RoiGeometry.builder().polygons(List.of(polygon)).build());
        req.setSnapshotBase64(null);

        CameraDetailResponse response = cameraService.updateRoiGeometry(testCameraId, req);

        assertNotNull(response);
        assertNotNull(response.getRoiGeometry());
        assertEquals(1, response.getRoiGeometry().getPolygons().size());
        verify(roiGeometryValidator).validate(any(RoiGeometry.class));
        verify(minioStorageService, never()).uploadRoiReferenceSnapshot(any(), anyString());
    }

    @Test
    @DisplayName("ROI (Hạng mục 3 - Error): updateRoiGeometry từ chối polygon ngoài [0, 1] bằng ERR_ROI_* và không lưu DB")
    void testUpdateRoiGeometry_invalidPolygon_throwsErrRoiAndDoesNotSave() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        doThrow(new CameraException(CameraErrorCode.ERR_ROI_003))
                .when(roiGeometryValidator).validate(any(RoiGeometry.class));

        RoiGeometry.RoiPolygon invalidPolygon = RoiGeometry.RoiPolygon.builder()
                .label("Out of bounds")
                .vertices(List.of(
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(-0.2)).y(java.math.BigDecimal.valueOf(0.1)).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(1.2)).y(java.math.BigDecimal.valueOf(0.1)).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(java.math.BigDecimal.valueOf(0.5)).y(java.math.BigDecimal.valueOf(0.9)).build()
                ))
                .build();
        RoiUpdateRequest req = new RoiUpdateRequest();
        req.setRoiGeometry(RoiGeometry.builder().polygons(List.of(invalidPolygon)).build());

        CameraException ex = assertThrows(CameraException.class,
                () -> cameraService.updateRoiGeometry(testCameraId, req));
        assertEquals(CameraErrorCode.ERR_ROI_003, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
        verify(minioStorageService, never()).uploadRoiReferenceSnapshot(any(), anyString());
    }

    @Test
    @DisplayName("SyncLiveStatus: Camera ONLINE nhưng MediaMTX ready=false quá thời gian khởi tạo -> chuyển OFFLINE và dừng AI worker")
    void testSyncLiveOperationalStatuses_unreadyPathPastGraceWindow_marksOffline() {
        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        testCamera.setUpdatedAt(java.time.OffsetDateTime.now().minusSeconds(60));

        when(mediaMtxService.formatPathName("CAM-001")).thenReturn("cam-001");
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Map.of("cam-001", false));
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.OFFLINE, testCamera.getOperationalStatus());
        verify(cameraRepository).save(argThat(c -> c.getOperationalStatus() == OperationalStatus.OFFLINE));
    }

    @Test
    @DisplayName("SyncLiveStatus: Camera ONLINE trong 25s đầu sau khi bật stream vẫn được giữ ONLINE để chờ MediaMTX kéo luồng")
    void testSyncLiveOperationalStatuses_unreadyPathWithinGraceWindow_staysOnline() {
        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        testCamera.setUpdatedAt(java.time.OffsetDateTime.now().minusSeconds(5));

        when(mediaMtxService.formatPathName("CAM-001")).thenReturn("cam-001");
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Map.of("cam-001", false));
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.ONLINE, testCamera.getOperationalStatus());
        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("SyncLiveStatus: MediaMTX trả về 0 path (emptyMap) khi camera đã ONLINE quá grace -> chuyển OFFLINE")
    void testSyncLiveOperationalStatuses_emptyMediaMtxPathsPastGrace_marksOffline() {
        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        testCamera.setUpdatedAt(java.time.OffsetDateTime.now().minusSeconds(60));

        when(mediaMtxService.formatPathName("CAM-001")).thenReturn("cam-001");
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Collections.emptyMap());
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.OFFLINE, testCamera.getOperationalStatus());
        verify(cameraRepository).save(argThat(c -> c.getOperationalStatus() == OperationalStatus.OFFLINE));
    }

    @Test
    @DisplayName("F-03: Camera đã xóa mềm (deletedAt != null) bị chặn ở getCameraDetail, updateCamera, decommissionCamera")
    void softDeletedCamera_isRejectedAcrossDetailUpdateDecommission() {
        testCamera.setDeletedAt(java.time.OffsetDateTime.now().minusMinutes(5));
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        CameraException exDetail = assertThrows(CameraException.class, () -> cameraService.getCameraDetail(testCameraId));
        assertEquals(CameraErrorCode.ERR_CAM_002, exDetail.getErrorCode());

        com.fa26se040.icss.dto.camera.UpdateCameraRequest updateReq =
                com.fa26se040.icss.dto.camera.UpdateCameraRequest.builder().name("Updated").build();
        CameraException exUpdate = assertThrows(CameraException.class, () -> cameraService.updateCamera(testCameraId, updateReq));
        assertEquals(CameraErrorCode.ERR_CAM_002, exUpdate.getErrorCode());

        CameraException exDecomm = assertThrows(CameraException.class, () -> cameraService.decommissionCamera(testCameraId));
        assertEquals(CameraErrorCode.ERR_CAM_002, exDecomm.getErrorCode());
    }

    @Test
    @DisplayName("F-03: Camera DECOMMISSIONED bị chặn ở updateCamera với ERR_STREAM_003")
    void decommissionedCamera_isRejectedOnUpdate() {
        testCamera.setStatus(CameraStatus.DECOMMISSIONED);
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        com.fa26se040.icss.dto.camera.UpdateCameraRequest updateReq =
                com.fa26se040.icss.dto.camera.UpdateCameraRequest.builder().name("Updated").build();
        CameraException ex = assertThrows(CameraException.class, () -> cameraService.updateCamera(testCameraId, updateReq));
        assertEquals(CameraErrorCode.ERR_STREAM_003, ex.getErrorCode());
    }

    @Test
    @DisplayName("F-04: Camera ONLINE + MediaMTX ready nhưng AI Worker chưa chạy -> tự động retry /api/v1/stream/start")
    void syncLiveOperationalStatuses_onlineCameraWithStoppedAiWorker_retriesAiWorkerStart() {
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "aiServiceUrl", "http://localhost:8000");
        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);

        when(mediaMtxService.formatPathName("CAM-001")).thenReturn("cam-001");
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Map.of("cam-001", true));
        when(mediaMtxService.getGatewayRtspUrl("CAM-001")).thenReturn("rtsp://mediamtx:8554/cam-001");
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001")).thenReturn(Optional.of(testCamera));
        when(restTemplate.getForObject("http://localhost:8000/api/v1/stream/status?camera_code=CAM-001", Map.class))
                .thenReturn(Map.of("is_running", false));

        cameraService.syncLiveOperationalStatuses();

        verify(restTemplate).postForObject(
                eq("http://localhost:8000/api/v1/stream/start"),
                argThat((Map<String, Object> body) -> "CAM-001".equals(body.get("camera_code"))),
                eq(Map.class)
        );
    }

    @Test
    @DisplayName("F-04: Cloud-Edge mode (Edge ONLINE) đọc trạng thái từ EdgeStatusService, không lật sai về OFFLINE khi MediaMTX local rỗng")
    void syncLiveOperationalStatuses_cloudEdgeOnline_usesEdgeHeartbeatWithoutFlippingOffline() {
        EdgeMqttControlService mockMqtt = mock(EdgeMqttControlService.class);
        EdgeStatusService mockEdgeStatus = mock(EdgeStatusService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "edgeMqttControlService", mockMqtt);
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "edgeStatusService", mockEdgeStatus);

        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        testCamera.setUpdatedAt(java.time.OffsetDateTime.now().minusMinutes(5));

        when(mockMqtt.isConnected()).thenReturn(true);
        when(mockEdgeStatus.hasActiveOnlineEdge()).thenReturn(true);
        when(mockEdgeStatus.getOnlineEdgeCameraStatuses()).thenReturn(Map.of("CAM-001", true));
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.ONLINE, testCamera.getOperationalStatus());
        verify(mediaMtxService, never()).getLivePathStatuses();
        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("F-04: Cloud-Edge mode đánh OFFLINE khi worker trên Edge ngừng chạy quá cửa sổ grace")
    void syncLiveOperationalStatuses_cloudEdgeWorkerStoppedPastGrace_marksOffline() {
        EdgeMqttControlService mockMqtt = mock(EdgeMqttControlService.class);
        EdgeStatusService mockEdgeStatus = mock(EdgeStatusService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "edgeMqttControlService", mockMqtt);
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "edgeStatusService", mockEdgeStatus);

        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.ONLINE);
        testCamera.setUpdatedAt(java.time.OffsetDateTime.now().minusMinutes(5));

        when(mockMqtt.isConnected()).thenReturn(true);
        when(mockEdgeStatus.hasActiveOnlineEdge()).thenReturn(true);
        when(mockEdgeStatus.getOnlineEdgeCameraStatuses()).thenReturn(Map.of("CAM-001", false));
        when(cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(testCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.OFFLINE, testCamera.getOperationalStatus());
        verify(cameraRepository).save(argThat(c -> c.getOperationalStatus() == OperationalStatus.OFFLINE));
    }

    @Test
    @DisplayName("F-08: upsertStreamConfig ném ERR_STREAM_005 (409) khi (host, port, mainStreamPath) đã bị camera ACTIVE khác sử dụng")
    void upsertStreamConfig_duplicateRtspEndpoint_throwsErrStream005() {
        testCamera.setStatus(CameraStatus.ACTIVE);
        testCamera.setOperationalStatus(OperationalStatus.OFFLINE);
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        when(cameraStreamConfigurationRepository.existsActiveRtspConflict(
                testCameraId,
                "192.168.1.8",
                554,
                "/stream",
                CameraStatus.DECOMMISSIONED
        )).thenReturn(true);

        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host(" 192.168.1.8 ")
                .port(554)
                .mainStreamPath("stream")
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.upsertStreamConfig(testCameraId, req));
        assertEquals(CameraErrorCode.ERR_STREAM_005, ex.getErrorCode());
        verify(cameraStreamConfigurationRepository, never()).save(any());
    }
}
