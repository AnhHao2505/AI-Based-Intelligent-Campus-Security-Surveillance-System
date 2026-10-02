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
    public ResponseEntity<ApiResponse<List<AreaSimpleResponse>>> getAvailableAreasForRequest(Authentication authentication) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.getAvailableAreasForRequest(actorEmail), "Lấy danh sách khu vực khả dụng thành công"));
    }

    @GetMapping("/map-pins")
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<AreaMapPinResponse>>> getMapPins(
            @RequestParam(required = false) String building
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                areaService.getMapPins(building),
                "Lấy danh sách điểm ghim khu vực trên bản đồ thành công"
        ));
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

    /**
     * Step 5b (BR-TC-03): xem trước tác động khi đổi loại khu vực (chỉ đọc).
     * Cùng hàm đánh giá với PUT; PUT đánh giá lại sau khi khoá.
     */
    @GetMapping("/{id}/type-change-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaTypeChangePreviewResponse>> previewTypeChange(
            @PathVariable UUID id,
            @RequestParam AreaLevel newAreaLevel
    ) {
        return ResponseEntity.ok(ApiResponse.success(areaService.previewTypeChange(id, newAreaLevel), "Xem trước đổi loại khu vực thành công"));
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
            // Step 5b (BR-TC-13): version khu vực gửi qua query vì AreaGeometry.version là phiên bản định dạng hình học
            @RequestParam(required = false) Long version,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(areaService.saveGeometry(id, geometry, version, actorEmail), "Lưu hình học khu vực thành công"));
    }

    @DeleteMapping("/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteGeometry(
            @PathVariable UUID id,
            @RequestParam(required = false) Long version,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        areaService.deleteGeometry(id, version, actorEmail);
        return ResponseEntity.ok(ApiResponse.success("Xóa hình học khu vực thành công"));
    }

    /**
     * Step 6 (BR-AD-01): vô hiệu hoá khu vực. Body {reason, version}. Thay cho DELETE /api/areas/{id} (đã bỏ).
     * Xem trước tác động: GET /{id}/dependencies (cùng hàm đánh giá).
     */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaResponse>> deactivate(
            @PathVariable UUID id,
            @RequestBody(required = false) AreaDeactivateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(areaService.deactivate(id, request, actorEmail), "Vô hiệu hóa khu vực thành công"));
    }

    /** Step 6 (BR-AD-07): khôi phục khu vực đã vô hiệu hoá. Body {reason, version}. */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaResponse>> restore(
            @PathVariable UUID id,
            @RequestBody(required = false) AreaRestoreRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(areaService.restore(id, request, actorEmail), "Khôi phục khu vực thành công"));
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AreaResponse>> updateAccessRules(
            @PathVariable UUID id,
            @Valid @RequestBody AreaAccessRulesUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.updateAccessRules(id, request, actorEmail), "Cập nhật quy tắc truy cập thành công"));
    }

    @PatchMapping("/{id}/event-mode")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<AreaResponse>> updateEventMode(
            @PathVariable UUID id,
            @RequestBody AreaEventModeUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.updateEventMode(id, request, actorEmail), "Cập nhật chế độ sự kiện thành công"));
    }

    @PostMapping("/{id}/event-schedules")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<EventScheduleResponse>> createEventSchedule(
            @PathVariable UUID id,
            @RequestBody EventScheduleRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        EventScheduleResponse response = areaService.createSchedule(id, request, actorEmail);
        URI location = URI.create("/api/areas/" + id + "/event-schedules/" + response.id());
        return ResponseEntity.created(location).body(ApiResponse.success(response, "Đặt lịch sự kiện thành công"));
    }

    @PatchMapping("/{id}/event-schedules/{scheduleId}")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<EventScheduleResponse>> updateEventSchedule(
            @PathVariable UUID id,
            @PathVariable UUID scheduleId,
            @RequestBody EventScheduleRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.updateSchedule(id, scheduleId, request, actorEmail), "Cập nhật lịch sự kiện thành công"));
    }

    @PostMapping("/{id}/event-schedules/{scheduleId}/cancel")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<EventScheduleResponse>> cancelEventSchedule(
            @PathVariable UUID id,
            @PathVariable UUID scheduleId,
            @RequestBody EventScheduleCancelRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(ApiResponse.success(areaService.cancelSchedule(id, scheduleId, request, actorEmail), "Huỷ lịch sự kiện thành công"));
    }

    @GetMapping("/{id}/event-schedules")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<EventScheduleResponse>>> getEventSchedules(
            @PathVariable UUID id,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(ApiResponse.success(areaService.getSchedules(id, status), "Lấy danh sách lịch sự kiện thành công"));
    }
}

