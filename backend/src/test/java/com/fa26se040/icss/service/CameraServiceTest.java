package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.CameraStreamConfigResponse;
import com.fa26se040.icss.dto.camera.CreateCameraRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
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
                .areas(new HashSet<>())
                .build();
    }

    @Test
    @DisplayName("CreateCamera: should auto-generate camera code using sequence (CAM-%03d)")
    void testCreateCameraAutoCodeFromSequence() {
        when(cameraRepository.getNextCameraCodeSequence()).thenReturn(5L);
        when(cameraRepository.save(any(Camera.class))).thenAnswer(invocation -> {
            Camera c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CreateCameraRequest req = CreateCameraRequest.builder()
                .name("Camera Tòa Nhà Alpha")
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
        when(cameraRepository.existsByCameraCode("CAM-999")).thenReturn(true);

        CreateCameraRequest req = CreateCameraRequest.builder()
                .cameraCode("CAM-999")
                .name("Camera Trùng")
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.createCamera(req));
        assertEquals(CameraErrorCode.ERR_CAM_003, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("ListCameras: forceSync=false should NOT query MediaMTX live status (<10ms target)")
    void testListCamerasWithoutForceSync() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Camera> cameraPage = new PageImpl<>(List.of(testCamera));
        when(cameraRepository.findFiltered(anyString(), any(), any(), eq(pageable))).thenReturn(cameraPage);

        cameraService.listCameras(null, null, null, false, pageable);

        verify(mediaMtxService, never()).getLivePathStatuses();
        verify(cameraRepository).findFiltered(anyString(), any(), any(), eq(pageable));
    }

    @Test
    @DisplayName("ListCameras: forceSync=true should query MediaMTX live status")
    void testListCamerasWithForceSync() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Camera> cameraPage = new PageImpl<>(List.of(testCamera));
        when(cameraRepository.findFiltered(anyString(), any(), any(), eq(pageable))).thenReturn(cameraPage);
        when(mediaMtxService.getLivePathStatuses()).thenReturn(Collections.emptyMap());

        cameraService.listCameras(null, null, null, true, pageable);

        verify(mediaMtxService).getLivePathStatuses();
    }

    @Test
    @DisplayName("UpsertStreamConfig: Gateway-First aborts DB commit when MediaMTX throws ERR_STREAM_002")
    void testUpsertStreamConfigGatewayFirstFailure() {
        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));
        when(cameraStreamConfigurationRepository.findByCameraId(testCameraId)).thenReturn(Optional.empty());

        doThrow(new CameraException(CameraErrorCode.ERR_STREAM_002))
                .when(mediaMtxService).syncCameraPathStrict(anyString(), anyString());

        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host("192.168.1.50")
                .port(554)
                .username("admin")
                .password("CameraPass@123")
                .mainStreamPath("/live/ch0")
                .build();

        CameraException ex = assertThrows(CameraException.class,
                () -> cameraService.upsertStreamConfig(testCameraId, req));

        assertEquals(CameraErrorCode.ERR_STREAM_002, ex.getErrorCode());
        // Verify DB was NOT saved
        verify(cameraStreamConfigurationRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpsertStreamConfig: Success path encrypts password at rest and returns zero-leakage response")
    void testUpsertStreamConfigSuccess() {
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

        // Verify Gateway was called before DB commit
        verify(mediaMtxService).syncCameraPathStrict(eq("CAM-001"), contains("Secret123"));
        verify(aesEncryptionUtil).encrypt("Secret123");
        verify(cameraStreamConfigurationRepository).save(any());
    }

    @Test
    @DisplayName("GetCameraAreas: should return assigned areas correctly")
    void testGetCameraAreas() {
        Area area = Area.builder()
                .id(UUID.randomUUID())
                .code("AREA-01")
                .name("Sảnh Chính")
                .areaLevel(AreaLevel.PUBLIC)
                .building("Tòa A")
                .floor("Tầng 1")
                .build();
        testCamera.getAreas().add(area);

        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        List<AreaSimpleResponse> areas = cameraService.getCameraAreas(testCameraId);

        assertEquals(1, areas.size());
        assertEquals("AREA-01", areas.get(0).code());
        assertEquals("Sảnh Chính", areas.get(0).name());
    }

    @Test
    @DisplayName("GetCameraDetail: should include assignedAreas in detail response")
    void testGetCameraDetailIncludesAssignedAreas() {
        Area area = Area.builder()
                .id(UUID.randomUUID())
                .code("AREA-02")
                .name("Phòng Server")
                .areaLevel(AreaLevel.PRIVATE)
                .building("Tòa B")
                .floor("Tầng 3")
                .build();
        testCamera.getAreas().add(area);

        when(cameraRepository.findById(testCameraId)).thenReturn(Optional.of(testCamera));

        CameraDetailResponse detail = cameraService.getCameraDetail(testCameraId);

        assertNotNull(detail);
        assertEquals("CAM-001", detail.getCameraCode());
        assertNotNull(detail.getAssignedAreas());
        assertEquals(1, detail.getAssignedAreas().size());
        assertEquals("AREA-02", detail.getAssignedAreas().get(0).code());
    }
}
