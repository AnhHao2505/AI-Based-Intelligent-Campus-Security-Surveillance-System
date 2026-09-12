package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraDetailResponse {
    private UUID id;
    private String cameraCode;
    private String name;
    private CameraStatus status;
    private OperationalStatus operationalStatus;
    private OffsetDateTime installedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private CameraStreamConfigResponse streamConfig;
    private List<AreaSimpleResponse> assignedAreas;
}
