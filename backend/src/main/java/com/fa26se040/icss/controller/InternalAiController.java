package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint nội bộ chỉ dành cho AI service, bảo vệ bằng header X-Service-Key (InternalServiceKeyFilter), không dùng JWT.
 */
@RestController
@RequestMapping("/api/internal/ai")
@RequiredArgsConstructor
public class InternalAiController {

    private final SystemConfigService systemConfigService;

    @GetMapping("/runtime-config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRuntimeConfig() {
        return ResponseEntity.ok(ApiResponse.success(
                systemConfigService.getAiRuntimeConfigMap(),
                "Lấy cấu hình runtime AI thành công"
        ));
    }
}
