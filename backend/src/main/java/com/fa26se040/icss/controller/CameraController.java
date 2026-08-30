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

import com.fa26se040.icss.dto.camera.*;
import com.fa26se040.icss.entity.CameraStatus;
import com.fa26se040.icss.entity.OperationalStatus;
import com.fa26se040.icss.service.CameraService;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraDetailResponse> create(@Valid @RequestBody CreateCameraRequest req) {
        log.info("REST request to create camera: {}", req.getName());
        CameraDetailResponse response = cameraService.createCamera(req);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<CameraListResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CameraStatus status,
            @RequestParam(required = false) OperationalStatus operationalStatus,
            @PageableDefault(size = 10) Pageable pageable) {
        log.info("REST request to list cameras with filters");
        Page<CameraListResponse> list = cameraService.listCameras(search, status, operationalStatus, pageable);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraDetailResponse> getDetail(@PathVariable UUID id) {
        log.info("REST request to get camera detail for id: {}", id);
        CameraDetailResponse detail = cameraService.getCameraDetail(id);
        return ResponseEntity.ok(detail);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraDetailResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateCameraRequest req) {
        log.info("REST request to update camera: {}", id);
        CameraDetailResponse updated = cameraService.updateCamera(id, req);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{id}/decommission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraDetailResponse> decommission(@PathVariable UUID id) {
        log.info("REST request to decommission camera: {}", id);
        CameraDetailResponse response = cameraService.decommissionCamera(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraDetailResponse> reactivate(@PathVariable UUID id) {
        log.info("REST request to reactivate camera: {}", id);
        CameraDetailResponse response = cameraService.reactivateCamera(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/specification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraSpecificationResponse> upsertSpec(@PathVariable UUID id, @Valid @RequestBody CameraSpecificationRequest req) {
        log.info("REST request to upsert camera spec: {}", id);
        CameraSpecificationResponse spec = cameraService.upsertSpecification(id, req);
        return ResponseEntity.ok(spec);
    }

    @PutMapping("/{id}/stream-config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraStreamConfigResponse> upsertStream(@PathVariable UUID id, @Valid @RequestBody CameraStreamConfigRequest req) {
        log.info("REST request to upsert camera stream configuration: {}", id);
        CameraStreamConfigResponse config = cameraService.upsertStreamConfig(id, req);
        return ResponseEntity.ok(config);
    }

    @PutMapping("/{id}/ai-config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CameraAIConfigResponse> upsertAI(@PathVariable UUID id, @Valid @RequestBody CameraAIConfigRequest req) {
        log.info("REST request to upsert camera AI configuration: {}", id);
        CameraAIConfigResponse config = cameraService.upsertAIConfig(id, req);
        return ResponseEntity.ok(config);
    }

    @GetMapping("/{id}/health-logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<Page<CameraHealthLogResponse>> healthLogs(
            @PathVariable UUID id,
            @PageableDefault(size = 10) Pageable pageable) {
        log.info("REST request to get health logs for camera: {}", id);
        Page<CameraHealthLogResponse> logs = cameraService.getHealthLogs(id, pageable);
        return ResponseEntity.ok(logs);
    }
}
