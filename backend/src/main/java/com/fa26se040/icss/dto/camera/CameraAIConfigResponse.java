package com.fa26se040.icss.dto.camera;

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
    private BigDecimal faceMatchThreshold;
    private Integer inferenceFps;
}
