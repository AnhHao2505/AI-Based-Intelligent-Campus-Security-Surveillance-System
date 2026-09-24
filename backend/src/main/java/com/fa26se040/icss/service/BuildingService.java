package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.building.BuildingDto;
import com.fa26se040.icss.dto.building.FloorDto;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingRepository buildingRepository;
    private final FloorRepository floorRepository;

    @Transactional(readOnly = true)
    public List<BuildingDto> getAllActiveBuildings() {
        List<Building> buildings = buildingRepository.findAllActiveWithFloors();
        return buildings.stream()
                .map(this::mapBuildingToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FloorDto> getFloorsByBuildingId(UUID buildingId) {
        List<Floor> floors = floorRepository.findByBuildingIdAndIsActiveTrueOrderByFloorOrderAsc(buildingId);
        return floors.stream()
                .map(this::mapFloorToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FloorDto> getAllActiveFloors() {
        List<Floor> floors = floorRepository.findAllActiveFloors();
        return floors.stream()
                .map(this::mapFloorToDto)
                .collect(Collectors.toList());
    }

    public BuildingDto mapBuildingToDto(Building b) {
        if (b == null) return null;
        List<FloorDto> floorDtos = b.getFloors() != null
                ? b.getFloors().stream()
                    .filter(f -> Boolean.TRUE.equals(f.getIsActive()))
                    .map(this::mapFloorToDto)
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return BuildingDto.builder()
                .id(b.getId())
                .code(b.getCode())
                .name(b.getName())
                .description(b.getDescription())
                .totalFloors(b.getTotalFloors())
                .isActive(b.getIsActive())
                .floors(floorDtos)
                .build();
    }

    public FloorDto mapFloorToDto(Floor f) {
        if (f == null) return null;
        return FloorDto.builder()
                .id(f.getId())
                .buildingId(f.getBuilding() != null ? f.getBuilding().getId() : null)
                .buildingCode(f.getBuilding() != null ? f.getBuilding().getCode() : null)
                .buildingName(f.getBuilding() != null ? f.getBuilding().getName() : null)
                .floorCode(f.getFloorCode())
                .name(f.getName())
                .floorOrder(f.getFloorOrder())
                .imageKey(f.getImageKey())
                .originalWidth(f.getOriginalWidth())
                .originalHeight(f.getOriginalHeight())
                .isActive(f.getIsActive())
                .areaCount(f.getAreas() != null ? (int) f.getAreas().stream().filter(a -> a.getDeletedAt() == null).count() : 0)
                .build();
    }
}
