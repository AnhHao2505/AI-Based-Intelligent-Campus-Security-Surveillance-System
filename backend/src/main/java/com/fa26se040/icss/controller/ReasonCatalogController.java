package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogCreateRequest;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogResponse;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogUpdateRequest;
import com.fa26se040.icss.service.ReasonCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reason-catalogs")
@RequiredArgsConstructor
public class ReasonCatalogController {

    private final ReasonCatalogService reasonCatalogService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ReasonCatalogResponse>> getAll(
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Boolean active
    ) {
        return ResponseEntity.ok(reasonCatalogService.getAll(actionType, active));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<List<ReasonCatalogResponse>> getActive(
            @RequestParam String actionType
    ) {
        return ResponseEntity.ok(reasonCatalogService.getActiveByActionType(actionType));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReasonCatalogResponse> create(
            @Valid @RequestBody ReasonCatalogCreateRequest request,
            Authentication authentication
    ) {
        ReasonCatalogResponse created = reasonCatalogService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReasonCatalogResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonCatalogUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(reasonCatalogService.update(id, request, authentication.getName()));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReasonCatalogResponse> deactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(reasonCatalogService.deactivate(id, authentication.getName()));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReasonCatalogResponse> reactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(reasonCatalogService.reactivate(id, authentication.getName()));
    }
}
