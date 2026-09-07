package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.ai.AiConfigRequest;
import com.fa26se040.icss.dto.ai.AiConfigResponse;
import com.fa26se040.icss.entity.AiConfiguration;
import com.fa26se040.icss.repository.AiConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConfigService {

    private final AiConfigurationRepository aiConfigurationRepository;

    @Transactional(readOnly = true)
    public AiConfigResponse getAiConfig() {
        AiConfiguration config = aiConfigurationRepository.findFirstByOrderByIdAsc()
                .orElseGet(this::createDefaultConfig);
        return mapToResponse(config);
    }

    @Transactional
    public AiConfigResponse updateAiConfig(AiConfigRequest request) {
        AiConfiguration config = aiConfigurationRepository.findFirstByOrderByIdAsc()
                .orElseGet(this::createDefaultConfig);

        config.setFaceMatchThreshold(request.getFaceMatchThreshold());
        config.setInferenceFps(request.getInferenceFps());

        AiConfiguration saved = aiConfigurationRepository.save(config);
        log.info("Cập nhật AI Configuration thành công: threshold={}, fps={}",
                saved.getFaceMatchThreshold(), saved.getInferenceFps());
        return mapToResponse(saved);
    }

    private AiConfiguration createDefaultConfig() {
        AiConfiguration defaultConfig = AiConfiguration.builder()
                .faceMatchThreshold(BigDecimal.valueOf(0.75))
                .inferenceFps(15)
                .build();
        return aiConfigurationRepository.save(defaultConfig);
    }

    private AiConfigResponse mapToResponse(AiConfiguration config) {
        return AiConfigResponse.builder()
                .id(config.getId())
                .faceMatchThreshold(config.getFaceMatchThreshold())
                .inferenceFps(config.getInferenceFps())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
