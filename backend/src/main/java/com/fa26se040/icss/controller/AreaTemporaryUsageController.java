package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaTemporaryUsageResponse;
import com.fa26se040.icss.dto.area.CreateTemporaryUsageRequest;
import com.fa26se040.icss.dto.area.ExtendTemporaryUsageRequest;
import com.fa26se040.icss.service.AreaTemporaryUsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/areas/{areaId}/temporary-usages")
@RequiredArgsConstructor
public class AreaTemporaryUsageController {

    private final AreaTemporaryUsageService temporaryUsageService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaTemporaryUsageResponse> create(
            @PathVariable UUID areaId,
            @Valid @RequestBody CreateTemporaryUsageRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaTemporaryUsageResponse response = temporaryUsageService.create(areaId, request, actorEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{temporaryUsageId}/extend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaTemporaryUsageResponse> extend(
            @PathVariable UUID areaId,
            @PathVariable UUID temporaryUsageId,
            @Valid @RequestBody ExtendTemporaryUsageRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaTemporaryUsageResponse response = temporaryUsageService.extend(areaId, temporaryUsageId, request, actorEmail);
        return ResponseEntity.ok(response);
    }
}
