package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.systemconfig.SystemConfigChangeLogResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigUpdateRequest;
import com.fa26se040.icss.service.SystemConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system-configurations")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<SystemConfigResponse>> getAll() {
        return ResponseEntity.ok(systemConfigService.getAll());
    }

    @PatchMapping("/{configKey}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SystemConfigResponse> update(
            @PathVariable String configKey,
            @Valid @RequestBody SystemConfigUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(systemConfigService.update(configKey, request.configValue(), authentication.getName()));
    }

    @GetMapping("/{configKey}/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<SystemConfigChangeLogResponse>> getHistory(
            @PathVariable String configKey,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return ResponseEntity.ok(systemConfigService.getHistory(configKey, pageable));
    }
}
