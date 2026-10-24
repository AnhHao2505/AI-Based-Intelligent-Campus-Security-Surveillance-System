package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.CameraStreamConfigResponse;
import com.fa26se040.icss.dto.camera.CreateCameraRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
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
    private CameraHealthLogRepository cameraHealthLogRepository;

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
        verify(cameraHealthLogRepository, never()).save(any());

        verify(aesEncryptionUtil).encrypt("Secret123");
        verify(cameraStreamConfigurationRepository).save(any());
        verify(cameraRepository).save(any());
    }

    @Test
    @DisplayName("DeleteCamera: happy path should soft-delete, unassign area (area=null), and clean MediaMTX path")
    void testDeleteCamera_happyPath_unassignsAreaAndCleansGateway() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        cameraService.deleteCamera(testCameraId);

        verify(cameraRepository).save(argThat(c ->
                c.getArea() == null &&
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
}
