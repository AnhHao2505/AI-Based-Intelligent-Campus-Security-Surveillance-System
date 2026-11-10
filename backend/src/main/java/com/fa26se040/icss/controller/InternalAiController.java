package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.ai.AiSyncDataDto;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.service.AiSyncService;
import com.fa26se040.icss.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
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
public class InternalAiController {

    private final SystemConfigService systemConfigService;
    private final AiSyncService aiSyncService;

    public InternalAiController(SystemConfigService systemConfigService) {
        this(systemConfigService, null);
    }

    @Autowired
    public InternalAiController(SystemConfigService systemConfigService,
                                @Autowired(required = false) AiSyncService aiSyncService) {
        this.systemConfigService = systemConfigService;
        this.aiSyncService = aiSyncService;
    }

    @GetMapping("/runtime-config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRuntimeConfig() {
        return ResponseEntity.ok(ApiResponse.success(
                systemConfigService.getAiRuntimeConfigMap(),
                "Lấy cấu hình runtime AI thành công"
        ));
    }

    @GetMapping("/sync-data")
    public ResponseEntity<ApiResponse<AiSyncDataDto>> getSyncData() {
        AiSyncDataDto data = aiSyncService != null ? aiSyncService.getAiSyncData() : null;
        return ResponseEntity.ok(ApiResponse.success(
                data,
                "Lấy dữ liệu đồng bộ AI thành công"
        ));
    }
}
