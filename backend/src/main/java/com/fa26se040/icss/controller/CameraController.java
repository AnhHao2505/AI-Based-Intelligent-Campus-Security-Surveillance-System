package com.fa26se040.icss.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.camera.*;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.service.CameraService;
import com.fa26se040.icss.service.CameraSnapshotService;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;
    private final CameraSnapshotService cameraSnapshotService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> create(@Valid @RequestBody CreateCameraRequest req) {
        log.info("REST request to create camera: {}", req.getName());
        CameraDetailResponse response = cameraService.createCamera(req);
        return new ResponseEntity<>(ApiResponse.created(response, "Thêm camera mới thành công"), HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<Page<CameraListResponse>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CameraStatus status,
            @RequestParam(required = false) OperationalStatus operationalStatus,
            @RequestParam(required = false, defaultValue = "false") Boolean forceSync,
            @PageableDefault(size = 10, sort = "cameraCode", direction = org.springframework.data.domain.Sort.Direction.ASC) Pageable pageable) {
        log.info("REST request to list cameras with filters, forceSync: {}", forceSync);
        Page<CameraListResponse> list = cameraService.listCameras(search, status, operationalStatus, forceSync, pageable);
        return ResponseEntity.ok(ApiResponse.success(list, "Lấy danh sách camera thành công"));
    }

    @GetMapping("/all-simple")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<List<CameraSimpleResponse>>> getAllSimple() {
        log.info("REST request to get simple active camera list");
        return ResponseEntity.ok(ApiResponse.success(cameraService.getAllActiveSimple(), "Lấy danh sách camera hoạt động thành công"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> getDetail(@PathVariable UUID id) {
        log.info("REST request to get camera detail for id: {}", id);
        CameraDetailResponse detail = cameraService.getCameraDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail, "Lấy thông tin chi tiết camera thành công"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> update(@PathVariable UUID id, @Valid @RequestBody UpdateCameraRequest req) {
        log.info("REST request to update camera: {}", id);
        CameraDetailResponse updated = cameraService.updateCamera(id, req);
        return ResponseEntity.ok(ApiResponse.success(updated, "Cập nhật thông tin camera thành công"));
    }

    @PatchMapping("/{id}/decommission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> decommission(@PathVariable UUID id) {
        log.info("REST request to decommission camera: {}", id);
        cameraService.decommissionCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã dừng hoạt động camera thành công"));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable UUID id) {
        log.info("REST request to reactivate camera: {}", id);
        cameraService.reactivateCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã kích hoạt lại camera thành công"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCamera(@PathVariable UUID id) {
        log.info("REST request to soft delete camera: {}", id);
        cameraService.deleteCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã xóa camera thành công"));
    }

    @PutMapping("/{id}/stream-config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraStreamConfigResponse>> upsertStream(@PathVariable UUID id, @Valid @RequestBody CameraStreamConfigRequest req) {
        log.info("REST request to upsert camera stream configuration: {}", id);
        CameraStreamConfigResponse config = cameraService.upsertStreamConfig(id, req);
        return ResponseEntity.ok(ApiResponse.success(config, "Cập nhật cấu hình stream thành công"));
    }

    @GetMapping("/{id}/health-logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<Page<CameraHealthLogResponse>>> healthLogs(
            @PathVariable UUID id,
            @PageableDefault(size = 10) Pageable pageable) {
        log.info("REST request to get health logs for camera: {}", id);
        Page<CameraHealthLogResponse> logs = cameraService.getHealthLogs(id, pageable);
        return ResponseEntity.ok(ApiResponse.success(logs, "Lấy nhật ký hoạt động camera thành công"));
    }

    @GetMapping("/{id}/areas")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<List<AreaSimpleResponse>>> getAreas(@PathVariable UUID id) {
        log.info("REST request to get areas assigned to camera: {}", id);
        return ResponseEntity.ok(ApiResponse.success(cameraService.getCameraAreas(id), "Lấy danh sách khu vực của camera thành công"));
    }

    @PostMapping("/{id}/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ConnectStreamResponse>> connectStream(@PathVariable UUID id) {
        log.info("REST request to connect RTSP stream for camera: {}", id);
        ConnectStreamResponse response = cameraSnapshotService.connectAndCapture(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Kết nối luồng stream camera thành công"));
    }

    @PostMapping("/{id}/test-connection")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TestConnectionResponse>> testConnection(@PathVariable UUID id) {
        log.info("REST request to test RTSP stream connection for camera: {}", id);
        TestConnectionResponse response = cameraSnapshotService.testConnection(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Kiểm tra kết nối camera hoàn tất"));
    }

    @PutMapping("/{id}/roi")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> updateRoi(
            @PathVariable UUID id,
            @Valid @RequestBody RoiUpdateRequest request) {
        log.info("REST request to update ROI geometry for camera: {}", id);
        CameraDetailResponse response = cameraService.updateRoiGeometry(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật cấu hình vùng giám sát ROI thành công"));
    }
}

