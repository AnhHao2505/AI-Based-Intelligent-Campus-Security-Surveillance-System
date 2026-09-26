package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.floorplan.FloorPlanResponse;
import com.fa26se040.icss.repository.FloorPlanRepository;
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
    private final com.fa26se040.icss.repository.FloorRepository floorRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<FloorPlanResponse>>> getFloorPlans() {
        List<com.fa26se040.icss.entity.Floor> floors = floorRepository.findAllActiveFloors();
        if (floors != null && !floors.isEmpty()) {
            List<FloorPlanResponse> responses = floors.stream()
                    .filter(f -> f.getImageKey() != null && !f.getImageKey().isBlank())
                    .map(f -> new FloorPlanResponse(
                            f.getId(),
                            f.getBuilding() != null ? f.getBuilding().getCode() : "FPT_AROUND",
                            f.getFloorCode(),
                            f.getImageKey(),
                            f.getOriginalWidth(),
                            f.getOriginalHeight(),
                            f.getIsActive()
                    ))
                    .toList();
            if (!responses.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(responses, "Lấy danh sách sơ đồ mặt bằng thành công"));
            }
        }

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
        return ResponseEntity.ok(ApiResponse.success(responses, "Lấy danh sách sơ đồ mặt bằng thành công"));
    }
}
