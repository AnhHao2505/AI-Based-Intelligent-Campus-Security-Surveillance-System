package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse;
import com.fa26se040.icss.enums.SecurityStatsRange;
import com.fa26se040.icss.service.SecurityDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Dashboard thống kê an ninh (chỉ đọc) cho FACILITY_MANAGER và ADMIN.
 * range sai (không thuộc SecurityStatsRange) → 400 qua handler MethodArgumentTypeMismatchException chung.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class SecurityDashboardController {

    private final SecurityDashboardService securityDashboardService;

    @GetMapping("/security-stats")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<SecurityStatsResponse>> getSecurityStats(
            @RequestParam(defaultValue = "LAST_7_DAYS") SecurityStatsRange range
    ) {
        SecurityStatsResponse stats = securityDashboardService.getStats(range, OffsetDateTime.now());
        return ResponseEntity.ok(ApiResponse.success(stats, "Lấy thống kê an ninh thành công"));
    }
}
