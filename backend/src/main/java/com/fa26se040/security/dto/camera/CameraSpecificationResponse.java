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
public class CameraSpecificationResponse {
    private UUID id;
    private String manufacturer;
    private String model;
    private String serialNumber;
    private String resolution;
    private Integer fps;
    private String lens;
    private String focalLength;
    private BigDecimal fieldOfView;
    private Boolean nightVision;
    private Boolean ptzSupported;
    private Boolean weatherProof;
    private String firmwareVersion;
}
