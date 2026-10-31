package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyUpdateRequest;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.service.IncidentPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Chính sách phân phối sự cố theo mức (V90, BR-DSP-12). Chỉ ADMIN. */
@RestController
@RequestMapping("/api/incident-dispatch-policies")
@RequiredArgsConstructor
public class IncidentDispatchPolicyController {

    private final IncidentPolicyService incidentPolicyService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<IncidentDispatchPolicyResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(incidentPolicyService.getDispatchPolicies(), "Lấy chính sách phân phối sự cố thành công"));
    }

    @PutMapping("/{severity}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<IncidentDispatchPolicyResponse>> update(
            @PathVariable IncidentSeverity severity,
            @Valid @RequestBody IncidentDispatchPolicyUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                incidentPolicyService.updateDispatchPolicy(severity, request, authentication.getName()), "Cập nhật chính sách phân phối sự cố thành công"));
    }
}
