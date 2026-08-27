package com.fa26se040.security.dto.camera;

import com.fa26se040.security.entity.CameraStatus;
import com.fa26se040.security.entity.OperationalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraListResponse {
    private UUID id;
    private String cameraCode;
    private String name;
    private Integer floor;
    private String zoneName;
    private CameraStatus status;
    private OperationalStatus operationalStatus;
}
