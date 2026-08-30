package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fa26se040.icss.entity.CameraStatus;
import com.fa26se040.icss.entity.OperationalStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraDetailResponse {
    private UUID id;
    private String cameraCode;
    private String name;
    private String description;
    private Integer floor;
    private String zoneName;
    private BigDecimal x;
    private BigDecimal y;
    private BigDecimal mountingHeight;
    private BigDecimal orientation;
    private BigDecimal tiltAngle;
    private CameraStatus status;
    private OperationalStatus operationalStatus;
    private OffsetDateTime installedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private CameraSpecificationResponse specification;
    private CameraStreamConfigResponse streamConfig;
    private CameraAIConfigResponse aiConfig;
}
