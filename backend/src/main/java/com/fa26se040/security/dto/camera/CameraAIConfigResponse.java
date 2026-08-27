package com.fa26se040.security.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraAIConfigResponse {
    private UUID id;
    private Boolean personDetectionEnabled;
    private Boolean faceRecognitionEnabled;
    private Boolean loiteringDetectionEnabled;
    private BigDecimal faceMatchThreshold;
    private Integer loiteringThresholdSeconds;
    private Integer inferenceFps;
    private String modelVersion;
}
