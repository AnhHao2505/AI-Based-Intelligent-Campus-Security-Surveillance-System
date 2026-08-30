package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

import com.fa26se040.icss.entity.CameraStatus;
import com.fa26se040.icss.entity.OperationalStatus;

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
