package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.area.*;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.service.AreaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/areas")
@RequiredArgsConstructor
public class AreaController {

    private final AreaService areaService;

    @GetMapping("/available-for-request")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<AreaSimpleResponse>>> getAvailableAreasForRequest() {
        return ResponseEntity.ok(ApiResponse.success(areaService.getAvailableAreasForRequest(), "Lấy danh sách khu vực khả dụng thành công"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<Page<AreaListItemResponse>>> getAreas(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AreaLevel areaLevel,
            @RequestParam(required = false) String building,
            @RequestParam(required = false, defaultValue = "true") Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort
    ) {
        int cappedSize = Math.min(Math.max(1, size), 100);
        String sortProperty = "name";
        Sort.Direction direction = Sort.Direction.ASC;
        if (sort != null && sort.contains(",")) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            if (!prop.equalsIgnoreCase("code") && !prop.isEmpty()) {
                sortProperty = prop;
            }
            if (parts.length > 1 && parts[1].equalsIgnoreCase("desc")) {
                direction = Sort.Direction.DESC;
            }
        }
        Sort sortOrder = Sort.by(direction, sortProperty);
        Pageable pageable = PageRequest.of(page, cappedSize, sortOrder);
        Page<AreaListItemResponse> result = areaService.getAreas(keyword, areaLevel, building, isActive, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách khu vực thành công"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<AreaResponse>> getAreaById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(areaService.getAreaById(id), "Lấy chi tiết khu vực thành công"));
    }

    @GetMapping("/{id}/dependencies")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaDependencyResponse>> getDependencies(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(areaService.getDependencies(id), "Kiểm tra ràng buộc khu vực thành công"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaResponse>> create(
            @Valid @RequestBody AreaCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaResponse response = areaService.create(request, actorEmail);
        URI location = URI.create("/api/areas/" + response.id());
        return ResponseEntity.created(location).body(ApiResponse.created(response, "Tạo mới khu vực thành công"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody AreaUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaResponse response = areaService.update(id, request, actorEmail);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật khu vực thành công"));
    }

    @GetMapping("/geometries")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<AreaGeometryResponse>>> getGeometries(
            @RequestParam String building,
            @RequestParam String floor
    ) {
        return ResponseEntity.ok(ApiResponse.success(areaService.getGeometriesByBuildingAndFloor(building, floor), "Lấy danh sách hình học khu vực thành công"));
    }

    @PatchMapping("/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaGeometryResponse>> saveGeometry(
            @PathVariable UUID id,
            @RequestBody AreaGeometry geometry,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(areaService.saveGeometry(id, geometry, actorEmail), "Lưu hình học khu vực thành công"));
    }

    @DeleteMapping("/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteGeometry(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        areaService.deleteGeometry(id, actorEmail);
        return ResponseEntity.ok(ApiResponse.success("Xóa hình học khu vực thành công"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        areaService.deactivate(id, actorEmail);
        return ResponseEntity.ok(ApiResponse.success("Vô hiệu hóa khu vực thành công"));
    }

    @GetMapping("/{id}/cameras")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<AreaCameraResponse>> getCameras(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(areaService.getCamerasForArea(id), "Lấy danh sách camera của khu vực thành công"));
    }

    @PutMapping("/{id}/cameras")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaCameraResponse>> updateCameras(
            @PathVariable UUID id,
            @RequestBody AreaCameraUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(areaService.updateCamerasForArea(id, request.getCameraIds()), "Cập nhật danh sách camera cho khu vực thành công"));
    }

    @PatchMapping("/{id}/access-rules")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<AreaResponse>> updateAccessRules(
            @PathVariable UUID id,
            @Valid @RequestBody AreaAccessRulesUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.updateAccessRules(id, request, actorEmail), "Cập nhật quy tắc truy cập thành công"));
    }
}

