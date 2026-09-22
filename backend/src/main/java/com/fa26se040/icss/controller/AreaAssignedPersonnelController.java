package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUpdateRequest;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.service.AreaAssignedPersonnelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * BR-AP-01: chỉ FACILITY_MANAGER được tạo, sửa, thu hồi. ADMIN chỉ xem.
 */
@RestController
@RequestMapping("/api/areas/{areaId}/assigned-personnel")
@RequiredArgsConstructor
public class AreaAssignedPersonnelController {

    private final AreaAssignedPersonnelService assignedPersonnelService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<List<AssignedPersonnelResponse>> getByArea(
            @PathVariable UUID areaId,
            @RequestParam(required = false) AssignedPersonnelStatus status
    ) {
        return ResponseEntity.ok(assignedPersonnelService.getByArea(areaId, status));
    }

    @PostMapping
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<AssignedPersonnelResponse> create(
            @PathVariable UUID areaId,
            @Valid @RequestBody AssignedPersonnelCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AssignedPersonnelResponse response = assignedPersonnelService.create(areaId, request, actorEmail);
        URI location = URI.create("/api/areas/" + areaId + "/assigned-personnel/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<AssignedPersonnelResponse> updateValidTo(
            @PathVariable UUID areaId,
            @PathVariable UUID id,
            @Valid @RequestBody AssignedPersonnelUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(assignedPersonnelService.updateValidTo(areaId, id, request, actorEmail));
    }

    @PatchMapping("/{id}/revoke")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<AssignedPersonnelResponse> revoke(
            @PathVariable UUID areaId,
            @PathVariable UUID id,
            @Valid @RequestBody AssignedPersonnelRevokeRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(assignedPersonnelService.revoke(areaId, id, request, actorEmail));
    }
}
