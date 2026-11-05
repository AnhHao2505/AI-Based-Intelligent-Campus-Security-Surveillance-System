package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.area.AreaCameraResponse;
import com.fa26se040.icss.dto.camera.CameraSimpleResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Xoá camera đang thuộc khu trên DB thật: flush không vi phạm NOT NULL của cameras.area_id (V76),
 * camera đã xoá giữ khu cũ và không còn trong danh sách camera đang dùng của khu
 * (GET /api/areas/{id}/cameras — popup "Cameras" và số camera trên thẻ khu).
 * MediaMTX và AI service được mock để test không gọi ra ngoài. Fixture rollback theo @Transactional.
 */
@Transactional
class CameraDeleteKeepsAreaIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CameraService cameraService;
    @Autowired private AreaService areaService;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private EntityManager entityManager;
    @MockBean private MediaMtxService mediaMtxService;
    @MockBean private AiServiceRestTemplate aiServiceRestTemplate;

    @Test
    @DisplayName("Delete camera of an area: no error, camera soft-deleted with its original area, gone from the area's camera list")
    void deleteCamera_keepsAreaAndLeavesActiveList() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Building building = buildingRepository.save(Building.builder().name("Tòa xoá camera " + suffix).build());
        Floor floor = floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build());
        Area area = areaRepository.save(Area.builder()
                .name("Khu xoá camera " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
        Camera kept = cameraRepository.save(Camera.builder()
                .cameraCode("DEL-KEEP-" + suffix)
                .name("Camera giữ lại " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(area)
                .build());
        Camera deleted = cameraRepository.save(Camera.builder()
                .cameraCode("DEL-GONE-" + suffix)
                .name("Camera bị xoá " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
        cameraRepository.flush();

        assertThatCode(() -> {
            cameraService.deleteCamera(deleted.getId());
            cameraRepository.flush();
        }).doesNotThrowAnyException();
        entityManager.clear();

        Camera reloaded = cameraRepository.findById(deleted.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(CameraStatus.DECOMMISSIONED);
        assertThat(reloaded.getOperationalStatus()).isEqualTo(OperationalStatus.OFFLINE);
        assertThat(reloaded.getArea().getId()).isEqualTo(area.getId());

        AreaCameraResponse areaCameras = areaService.getCamerasForArea(area.getId());
        assertThat(areaCameras.getCameras()).extracting(CameraSimpleResponse::getId)
                .containsExactly(kept.getId());
    }
}
