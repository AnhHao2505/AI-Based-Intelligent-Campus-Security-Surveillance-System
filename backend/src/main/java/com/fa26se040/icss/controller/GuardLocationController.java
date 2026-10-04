package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.geofence.CampusGeofenceDto;
import com.fa26se040.icss.dto.guard.GuardLocationResponse;
import com.fa26se040.icss.dto.guard.GuardLocationUpdateRequest;
import com.fa26se040.icss.service.CampusGeofenceService;
import com.fa26se040.icss.service.GuardLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GuardLocationController {

    private final GuardLocationService guardLocationService;
    private final CampusGeofenceService campusGeofenceService;

    /**
     * Cập nhật vị trí GPS tự động từ mobile của nhân viên bảo vệ.
     */
    @PostMapping("/guards/me/location")
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuardLocationResponse>> updateMyLocation(
            @Valid @RequestBody GuardLocationUpdateRequest request,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        GuardLocationResponse response = guardLocationService.updateGuardLocation(userEmail, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật vị trí thành công"));
    }

    /**
     * Lấy danh sách vị trí thời gian thực của các bảo vệ đang hoạt động (phục vụ hiển thị trên bản đồ).
     */
    @GetMapping("/guards/active-locations")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<List<GuardLocationResponse>>> getActiveGuardLocations() {
        List<GuardLocationResponse> list = guardLocationService.getActiveGuardLocations();
        return ResponseEntity.ok(ApiResponse.success(list, "Lấy danh sách vị trí bảo vệ thành công"));
    }

    /**
     * Lấy thông tin Geofence duy nhất của toàn bộ khuôn viên trường.
     */
    @GetMapping("/campus/geofence")
    public ResponseEntity<ApiResponse<CampusGeofenceDto>> getCampusGeofence() {
        CampusGeofenceDto geofence = campusGeofenceService.getCampusGeofence();
        return ResponseEntity.ok(ApiResponse.success(geofence, "Lấy thông tin Geofence khuôn viên thành công"));
    }

    /**
     * Cập nhật ranh giới Geofence do ADMIN vẽ lại trên bản đồ.
     */
    @PutMapping("/campus/geofence")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CampusGeofenceDto>> updateCampusGeofence(
            @Valid @RequestBody com.fa26se040.icss.dto.geofence.CampusGeofenceUpdateRequest request,
            Authentication authentication
    ) {
        String adminEmail = authentication.getName();
        CampusGeofenceDto response = campusGeofenceService.updateCampusGeofence(request, adminEmail);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật ranh giới Geofence khuôn viên thành công"));
    }
}
