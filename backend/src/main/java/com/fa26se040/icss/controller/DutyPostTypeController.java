package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.incidentpolicy.DutyPostTypeResponse;
import com.fa26se040.icss.dto.incidentpolicy.DutyPostTypeUpdateRequest;
import com.fa26se040.icss.service.IncidentPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Danh mục vị trí trực (V90, BR-DSP-12 / BR-DSP-13). ADMIN sửa; FM đọc danh sách đang dùng để xếp ca. */
@RestController
@RequestMapping("/api/duty-post-types")
@RequiredArgsConstructor
public class DutyPostTypeController {

    private final IncidentPolicyService incidentPolicyService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<DutyPostTypeResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(incidentPolicyService.getDutyPostTypes(), "Lấy danh mục vị trí trực thành công"));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<DutyPostTypeResponse>>> getActive() {
        return ResponseEntity.ok(ApiResponse.success(incidentPolicyService.getActiveDutyPostTypes(), "Lấy danh mục vị trí trực đang sử dụng thành công"));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DutyPostTypeResponse>> update(
            @PathVariable String code,
            @Valid @RequestBody DutyPostTypeUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                incidentPolicyService.updateDutyPostType(code, request, authentication.getName()), "Cập nhật vị trí trực thành công"));
    }
}
