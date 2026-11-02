package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentSeverityRuleResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentSeverityRuleUpdateRequest;
import com.fa26se040.icss.service.IncidentPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Luật mức độ sự cố (V90, BR-SEV-04). Chỉ ADMIN. */
@RestController
@RequestMapping("/api/incident-severity-rules")
@RequiredArgsConstructor
public class IncidentSeverityRuleController {

    private final IncidentPolicyService incidentPolicyService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<IncidentSeverityRuleResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(incidentPolicyService.getSeverityRules(), "Lấy luật mức độ sự cố thành công"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<IncidentSeverityRuleResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody IncidentSeverityRuleUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                incidentPolicyService.updateSeverityRule(id, request, authentication.getName()), "Cập nhật luật mức độ sự cố thành công"));
    }
}
