package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.dto.systemconfig.SystemConfigChangeLogResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigUpdateRequest;
import com.fa26se040.icss.service.ReasonValidator;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system-configurations")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final ReasonValidator reasonValidator;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<SystemConfigResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(systemConfigService.getAll(), "Lấy danh sách cấu hình hệ thống thành công"));
    }

    /**
     * Checklist 6.1: độ dài lý do đang hiệu lực (REASON_MIN_LENGTH / REASON_MAX_LENGTH) cho mọi tài khoản đăng nhập,
     * để form nhập lý do kiểm trước khi gửi. Chỉ trả 2 giá trị này, không mở danh sách cấu hình cho mọi role.
     */
    @GetMapping("/reason-limits")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ReasonLimits>> getReasonLimits() {
        return ResponseEntity.ok(ApiResponse.success(reasonValidator.limits(), "Lấy giới hạn độ dài lý do thành công"));
    }

    @PatchMapping("/{configKey}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SystemConfigResponse>> update(
            @PathVariable String configKey,
            @Valid @RequestBody SystemConfigUpdateRequest request,
            Authentication authentication
    ) {
        SystemConfigResponse response = systemConfigService.update(configKey, request.configValue(), request.reason(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật cấu hình hệ thống thành công"));
    }

    @GetMapping("/{configKey}/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<SystemConfigChangeLogResponse>>> getHistory(
            @PathVariable String configKey,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success(systemConfigService.getHistory(configKey, pageable), "Lấy lịch sử thay đổi cấu hình thành công"));
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reloadCache() {
        int loadedKeys = systemConfigService.reloadCache();
        return ResponseEntity.ok(ApiResponse.success(Map.of("loadedKeys", loadedKeys), "Nạp lại bộ nhớ đệm cấu hình thành công"));
    }
}
