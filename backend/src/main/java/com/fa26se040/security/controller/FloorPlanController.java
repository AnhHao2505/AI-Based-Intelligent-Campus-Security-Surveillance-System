package com.fa26se040.security.controller;

import com.fa26se040.security.dto.floorplan.FloorPlanResponse;
import com.fa26se040.security.repository.FloorPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/floor-plans")
@RequiredArgsConstructor
public class FloorPlanController {

    private final FloorPlanRepository floorPlanRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<FloorPlanResponse>> getFloorPlans() {
        List<FloorPlanResponse> responses = floorPlanRepository.findByIsActiveTrueOrderByBuildingAscFloorAsc()
                .stream()
                .map(fp -> new FloorPlanResponse(
                        fp.getId(),
                        fp.getBuilding(),
                        fp.getFloor(),
                        fp.getImageKey(),
                        fp.getOriginalWidth(),
                        fp.getOriginalHeight(),
                        fp.getIsActive()
                ))
                .toList();
        return ResponseEntity.ok(responses);
    }
}
