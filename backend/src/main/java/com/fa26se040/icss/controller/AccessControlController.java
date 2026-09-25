package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesscontrol.AccessControlAuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.service.AccessControlAuditService;
import com.fa26se040.icss.service.AreaLevelPresetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/access-control")
@RequiredArgsConstructor
public class AccessControlController {

    private final AreaLevelPresetService areaLevelPresetService;
    private final AccessControlAuditService auditService;

    @GetMapping("/level-presets")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<LevelPresetResponse>> getLevelPresets() {
        return ResponseEntity.ok(areaLevelPresetService.getAllPresets());
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/level-presets/{areaLevel}",
            method = {org.springframework.web.bind.annotation.RequestMethod.PUT, org.springframework.web.bind.annotation.RequestMethod.PATCH}
    )
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<LevelPresetResponse> updateLevelPreset(
            @PathVariable AreaLevel areaLevel,
            @Valid @RequestBody LevelPresetUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(areaLevelPresetService.updatePreset(areaLevel, request, actorEmail));
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<Page<AccessControlAuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) AccessControlTargetType targetType,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) UUID subjectUserId,
            @RequestParam(required = false) UUID changedBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 10, sort = "changedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(auditService.getAuditLogs(
                targetType,
                areaId,
                subjectUserId,
                changedBy,
                from,
                to,
                pageable
        ));
    }
}
