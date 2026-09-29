package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
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
    public ResponseEntity<ApiResponse<List<ReasonCatalogResponse>>> getAll(
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Boolean active
    ) {
        return ResponseEntity.ok(ApiResponse.success(reasonCatalogService.getAll(actionType, active), "Lấy danh mục lý do thành công"));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ReasonCatalogResponse>>> getActive(
            @RequestParam String actionType
    ) {
        return ResponseEntity.ok(ApiResponse.success(reasonCatalogService.getActiveByActionType(actionType), "Lấy danh mục lý do khả dụng thành công"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReasonCatalogResponse>> create(
            @Valid @RequestBody ReasonCatalogCreateRequest request,
            Authentication authentication
    ) {
        ReasonCatalogResponse created = reasonCatalogService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created, "Tạo lý do danh mục thành công"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReasonCatalogResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonCatalogUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(reasonCatalogService.update(id, request, authentication.getName()), "Cập nhật lý do danh mục thành công"));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReasonCatalogResponse>> deactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(reasonCatalogService.deactivate(id, authentication.getName()), "Vô hiệu hóa lý do danh mục thành công"));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReasonCatalogResponse>> reactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(reasonCatalogService.reactivate(id, authentication.getName()), "Kích hoạt lại lý do danh mục thành công"));
    }
}
