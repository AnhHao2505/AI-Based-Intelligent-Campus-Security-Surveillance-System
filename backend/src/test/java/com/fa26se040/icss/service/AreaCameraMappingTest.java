package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaCameraResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AreaCameraMappingTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaValidator areaValidator;

    @Mock
    private AreaDependencyChecker dependencyChecker;

    @Mock
    private AreaGeometryValidator geometryValidator;

    @InjectMocks
    private AreaService areaService;

    private Area testArea;
    private UUID testAreaId;

    @BeforeEach
    void setUp() {
        testAreaId = UUID.randomUUID();
        testArea = Area.builder()
                .id(testAreaId)
                .code("AREA-LAB-01")
                .name("Phòng Lab AI")
                .areaLevel(AreaLevel.SEMI_PRIVATE)
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .cameras(new HashSet<>())
                .build();
    }

    @Test
    @DisplayName("UpdateCamerasForArea: should assign active cameras successfully")
    void testUpdateCamerasForAreaSuccess() {
        UUID camId1 = UUID.randomUUID();
        Camera cam1 = Camera.builder()
                .id(camId1)
                .cameraCode("CAM-001")
                .name("Camera 1")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .build();

        when(areaRepository.findByIdAndDeletedAtIsNull(testAreaId)).thenReturn(Optional.of(testArea));
        when(cameraRepository.findAllById(List.of(camId1))).thenReturn(List.of(cam1));
        when(areaRepository.save(any(Area.class))).thenAnswer(i -> i.getArgument(0));

        AreaCameraResponse resp = areaService.updateCamerasForArea(testAreaId, List.of(camId1));

        assertNotNull(resp);
        assertEquals(testAreaId, resp.getAreaId());
        assertEquals("AREA-LAB-01", resp.getAreaCode());
        assertEquals(1, resp.getCameras().size());
        assertEquals("CAM-001", resp.getCameras().get(0).getCameraCode());
    }

    @Test
    @DisplayName("UpdateCamerasForArea: should throw ERR_CAM_002 if any camera ID does not exist")
    void testUpdateCamerasForAreaMissingCamera() {
        UUID camId1 = UUID.randomUUID();
        UUID camId2 = UUID.randomUUID();

        when(areaRepository.findByIdAndDeletedAtIsNull(testAreaId)).thenReturn(Optional.of(testArea));
        // Only one camera found out of two
        when(cameraRepository.findAllById(List.of(camId1, camId2))).thenReturn(List.of(
                Camera.builder().id(camId1).status(CameraStatus.ACTIVE).build()
        ));

        CameraException ex = assertThrows(CameraException.class,
                () -> areaService.updateCamerasForArea(testAreaId, List.of(camId1, camId2)));

        assertEquals(CameraErrorCode.ERR_CAM_002, ex.getErrorCode());
        verify(areaRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateCamerasForArea: should throw ERR_MAP_002 if attempting to assign DECOMMISSIONED camera")
    void testUpdateCamerasForAreaDecommissionedCamera() {
        UUID camId1 = UUID.randomUUID();
        Camera decommCam = Camera.builder()
                .id(camId1)
                .cameraCode("CAM-DECOMM")
                .name("Camera Cũ")
                .status(CameraStatus.DECOMMISSIONED)
                .build();

        when(areaRepository.findByIdAndDeletedAtIsNull(testAreaId)).thenReturn(Optional.of(testArea));
        when(cameraRepository.findAllById(List.of(camId1))).thenReturn(List.of(decommCam));

        CameraException ex = assertThrows(CameraException.class,
                () -> areaService.updateCamerasForArea(testAreaId, List.of(camId1)));

        assertEquals(CameraErrorCode.ERR_MAP_002, ex.getErrorCode());
        verify(areaRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateCamerasForArea: empty list should clear all assigned cameras")
    void testUpdateCamerasForAreaClearCameras() {
        testArea.getCameras().add(Camera.builder().id(UUID.randomUUID()).status(CameraStatus.ACTIVE).build());

        when(areaRepository.findByIdAndDeletedAtIsNull(testAreaId)).thenReturn(Optional.of(testArea));
        when(areaRepository.save(any(Area.class))).thenAnswer(i -> i.getArgument(0));

        AreaCameraResponse resp = areaService.updateCamerasForArea(testAreaId, Collections.emptyList());

        assertNotNull(resp);
        assertTrue(resp.getCameras().isEmpty());
    }
}
