package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.ai.AiConfigRequest;
import com.fa26se040.icss.dto.ai.AiConfigResponse;
import com.fa26se040.icss.service.AiConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
public class AiConfigController {

    private final AiConfigService aiConfigService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD')")
    public ResponseEntity<AiConfigResponse> getAiConfig() {
        return ResponseEntity.ok(aiConfigService.getAiConfig());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AiConfigResponse> updateAiConfig(@Valid @RequestBody AiConfigRequest request) {
        return ResponseEntity.ok(aiConfigService.updateAiConfig(request));
    }
}
