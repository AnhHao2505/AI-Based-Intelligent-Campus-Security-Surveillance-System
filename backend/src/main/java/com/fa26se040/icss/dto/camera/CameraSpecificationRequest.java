package com.fa26se040.icss.dto.camera;

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
public class CameraSpecificationRequest {

    @Size(max = 100, message = "Manufacturer cannot exceed 100 characters")
    private String manufacturer;

    @Size(max = 100, message = "Model cannot exceed 100 characters")
    private String model;

    @Size(max = 100, message = "Serial number cannot exceed 100 characters")
    private String serialNumber;

    @Size(max = 50, message = "Resolution cannot exceed 50 characters")
    private String resolution;

    private Integer fps;

    @Size(max = 100, message = "Lens cannot exceed 100 characters")
    private String lens;

    @Size(max = 50, message = "Focal length cannot exceed 50 characters")
    private String focalLength;

    private BigDecimal fieldOfView;

    private Boolean nightVision;

    private Boolean ptzSupported;

    private Boolean weatherProof;

    @Size(max = 50, message = "Firmware version cannot exceed 50 characters")
    private String firmwareVersion;
}
