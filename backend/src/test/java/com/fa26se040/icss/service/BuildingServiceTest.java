package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.building.BuildingDto;
import com.fa26se040.icss.dto.building.FloorDto;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildingServiceTest {

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private FloorRepository floorRepository;

    @InjectMocks
    private BuildingService buildingService;

    @Test
    @DisplayName("getAllActiveBuildings returns mapped building DTOs")
    void testGetAllActiveBuildings() {
        Building b = Building.builder()
                .id(UUID.randomUUID())
                .code("TOA_ALPHA")
                .name("Tòa Alpha")
                .totalFloors(5)
                .isActive(true)
                .build();
        Floor f = Floor.builder()
                .id(UUID.randomUUID())
                .building(b)
                .floorCode("TANG_1")
                .name("Tầng 1")
                .floorOrder(1)
                .isActive(true)
                .build();
        b.setFloors(List.of(f));

        when(buildingRepository.findAllActiveWithFloors()).thenReturn(List.of(b));

        List<BuildingDto> result = buildingService.getAllActiveBuildings();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("TOA_ALPHA", result.get(0).getCode());
        assertEquals(1, result.get(0).getFloors().size());
        assertEquals("TANG_1", result.get(0).getFloors().get(0).getFloorCode());
    }

    @Test
    @DisplayName("getFloorsByBuildingId returns mapped floor DTOs")
    void testGetFloorsByBuildingId() {
        UUID buildingId = UUID.randomUUID();
        Building b = Building.builder()
                .id(buildingId)
                .code("TOA_BETA")
                .name("Tòa Beta")
                .build();
        Floor f = Floor.builder()
                .id(UUID.randomUUID())
                .building(b)
                .floorCode("TANG_G")
                .name("Tầng Trệt")
                .floorOrder(0)
                .isActive(true)
                .build();

        when(floorRepository.findByBuildingIdAndIsActiveTrueOrderByFloorOrderAsc(buildingId))
                .thenReturn(List.of(f));

        List<FloorDto> result = buildingService.getFloorsByBuildingId(buildingId);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("TANG_G", result.get(0).getFloorCode());
        assertEquals("TOA_BETA", result.get(0).getBuildingCode());
    }
}
