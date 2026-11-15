package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.dto.camera.CameraMapLocationRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
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
    @Mock private AiServiceRestTemplate restTemplate;

    @InjectMocks
    private CameraService cameraService;

    private Camera buildActiveCamera(UUID id) {
        Area area = Area.builder()
                .id(UUID.randomUUID())
                .name("KT Điện")
                .building("Tòa Epsilon")
                .floor("Tầng 3")
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

        assertThrows(IllegalArgumentException.class, () -> cameraService.updateMapLocation(id, req));
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
