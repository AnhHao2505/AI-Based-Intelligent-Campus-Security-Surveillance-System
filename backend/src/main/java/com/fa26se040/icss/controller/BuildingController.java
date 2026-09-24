package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.building.BuildingDto;
import com.fa26se040.icss.dto.building.FloorDto;
import com.fa26se040.icss.service.BuildingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/buildings")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<BuildingDto>> getAllBuildings() {
        return ResponseEntity.ok(buildingService.getAllActiveBuildings());
    }

    @GetMapping("/{id}/floors")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<FloorDto>> getFloorsByBuilding(@PathVariable UUID id) {
        return ResponseEntity.ok(buildingService.getFloorsByBuildingId(id));
    }

    @GetMapping("/floors")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<FloorDto>> getAllFloors() {
        return ResponseEntity.ok(buildingService.getAllActiveFloors());
    }
}
