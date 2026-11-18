package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.dto.camera.CameraMapLocationRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.util.AesEncryptionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraServiceMapLocationTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    @Mock private MediaMtxService mediaMtxService;
    @Mock private AesEncryptionUtil aesEncryptionUtil;
    @Mock private RoiGeometryValidator roiGeometryValidator;
    @Mock private MinioStorageService minioStorageService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private AreaRepository areaRepository;
    @Mock private FloorRepository floorRepository;
    @Mock private AiServiceRestTemplate restTemplate;

    @InjectMocks
    private CameraService cameraService;

    @org.junit.jupiter.api.BeforeEach
    void injectFloorRepo() {
        org.springframework.test.util.ReflectionTestUtils.setField(cameraService, "floorRepository", floorRepository);
    }

    private Floor epsilonFloor3() {
        Building b = Building.builder().id(UUID.randomUUID()).name("Tòa Epsilon").isActive(true).build();
        return Floor.builder().id(UUID.randomUUID()).building(b).name("Tầng 3").floorOrder(2).isActive(true).build();
    }

    private Camera buildActiveCamera(UUID id) {
        Floor fl = epsilonFloor3();
        Area area = Area.builder()
                .id(UUID.randomUUID())
                .name("KT Điện")
                .building("Tòa Epsilon")
                .floor("Tầng 3")
                .floorEntity(fl)
                .build();
        return Camera.builder()
                .id(id)
                .cameraCode("CAM-019")
                .name("ADAD")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build();
    }

    @Test
    void updateMapLocation_savesCoordinatesAndReturnsDetail() {
        UUID id = UUID.randomUUID();
        Camera cam = buildActiveCamera(id);
        when(cameraRepository.findById(id)).thenReturn(Optional.of(cam));
        when(floorRepository.findAllActiveFloors()).thenReturn(java.util.List.of(epsilonFloor3()));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        CameraMapLocationRequest req = CameraMapLocationRequest.builder()
                .longitude(108.26105)
                .latitude(15.96885)
                .building("  Tòa Epsilon  ")
                .floorOrder(2)
                .build();

        CameraDetailResponse res = cameraService.updateMapLocation(id, req);

        assertEquals(108.26105, res.getMapLongitude());
        assertEquals(15.96885, res.getMapLatitude());
        assertEquals("Tòa Epsilon", res.getMapBuilding());
        assertEquals(2, res.getMapFloorOrder());
        verify(cameraRepository).save(cam);
    }

    @Test
    void updateMapLocation_rejectsSoftDeletedCamera() {
        UUID id = UUID.randomUUID();
        Camera cam = buildActiveCamera(id);
        cam.setDeletedAt(OffsetDateTime.now());
        when(cameraRepository.findById(id)).thenReturn(Optional.of(cam));

        CameraMapLocationRequest req = CameraMapLocationRequest.builder()
                .longitude(108.26105)
                .latitude(15.96885)
                .build();

        assertThrows(CameraException.class, () -> cameraService.updateMapLocation(id, req));
        verify(cameraRepository, never()).save(any());
    }

    @Test
    void updateMapLocation_rejectsOutOfRangeCoordinates() {
        UUID id = UUID.randomUUID();
        Camera cam = buildActiveCamera(id);
        when(cameraRepository.findById(id)).thenReturn(Optional.of(cam));

        CameraMapLocationRequest req = CameraMapLocationRequest.builder()
                .longitude(181.0)
                .latitude(15.96885)
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.updateMapLocation(id, req));
        assertEquals(CameraErrorCode.ERR_MAP_003, ex.getErrorCode());
        verify(cameraRepository, never()).save(any());
    }

    @Test
    void updateMapLocation_rejectsMismatchedBuildingOrFloorOrder() {
        UUID id = UUID.randomUUID();
        Camera cam = buildActiveCamera(id);
        when(cameraRepository.findById(id)).thenReturn(Optional.of(cam));
        Building alpha = Building.builder().id(UUID.randomUUID()).name("Tòa Alpha").isActive(true).build();
        Floor alphaF1 = Floor.builder().id(UUID.randomUUID()).building(alpha).name("Tầng 1").floorOrder(1).isActive(true).build();
        when(floorRepository.findAllActiveFloors()).thenReturn(java.util.List.of(epsilonFloor3(), alphaF1));

        // Sai tòa nhà
        CameraMapLocationRequest wrongBuildingReq = CameraMapLocationRequest.builder()
                .longitude(108.26105)
                .latitude(15.96885)
                .building("Tòa Alpha")
                .floorOrder(1)
                .build();
        CameraException exBldg = assertThrows(CameraException.class, () -> cameraService.updateMapLocation(id, wrongBuildingReq));
        assertEquals(CameraErrorCode.ERR_MAP_004, exBldg.getErrorCode());

        // Đúng tòa nhà nhưng sai tầng
        CameraMapLocationRequest wrongFloorReq = CameraMapLocationRequest.builder()
                .longitude(108.26105)
                .latitude(15.96885)
                .building("Tòa Epsilon")
                .floorOrder(0)
                .build();
        CameraException exFloor = assertThrows(CameraException.class, () -> cameraService.updateMapLocation(id, wrongFloorReq));
        assertEquals(CameraErrorCode.ERR_MAP_004, exFloor.getErrorCode());

        verify(cameraRepository, never()).save(any());
    }

    @Test
    void clearMapLocation_nullsAllMapFields() {
        UUID id = UUID.randomUUID();
        Camera cam = buildActiveCamera(id);
        cam.setMapLongitude(108.26105);
        cam.setMapLatitude(15.96885);
        cam.setMapBuilding("Tòa Epsilon");
        cam.setMapFloorOrder(2);

        when(cameraRepository.findById(id)).thenReturn(Optional.of(cam));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));

        CameraDetailResponse res = cameraService.clearMapLocation(id);

        assertNull(res.getMapLongitude());
        assertNull(res.getMapLatitude());
        assertNull(res.getMapBuilding());
        assertNull(res.getMapFloorOrder());
        verify(cameraRepository).save(cam);
    }
}
