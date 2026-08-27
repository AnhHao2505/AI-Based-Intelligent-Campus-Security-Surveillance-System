package com.fa26se040.security.dto.camera;

import jakarta.validation.constraints.NotBlank;
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
public class CreateCameraRequest {

    @Size(max = 50, message = "Camera code cannot exceed 50 characters")
    private String cameraCode;

    @NotBlank(message = "Camera name is required")
    @Size(max = 100, message = "Camera name cannot exceed 100 characters")
    private String name;

    private Integer floor;

    @Size(max = 255, message = "Zone name cannot exceed 255 characters")
    private String zoneName;

    private BigDecimal x;

    private BigDecimal y;

    private BigDecimal mountingHeight;

    private BigDecimal orientation;

    private BigDecimal tiltAngle;
}
