package com.fa26se040.security.dto.camera;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraAIConfigRequest {

    private Boolean personDetectionEnabled;

    private Boolean faceRecognitionEnabled;

    private Boolean loiteringDetectionEnabled;

    @NotNull(message = "Face match threshold is required")
    @DecimalMin(value = "0.0", message = "Face match threshold must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Face match threshold must be at most 1.0")
    private BigDecimal faceMatchThreshold;

    @NotNull(message = "Loitering threshold seconds is required")
    @Min(value = 0, message = "Loitering threshold must be at least 0 seconds")
    private Integer loiteringThresholdSeconds;

    @NotNull(message = "Inference FPS is required")
    @Min(value = 1, message = "Inference FPS must be at least 1")
    private Integer inferenceFps;

    @Size(max = 50, message = "Model version cannot exceed 50 characters")
    private String modelVersion;
}
