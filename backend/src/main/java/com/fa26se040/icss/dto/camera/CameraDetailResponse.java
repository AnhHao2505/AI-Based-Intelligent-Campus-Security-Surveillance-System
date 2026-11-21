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
import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.OperationalStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraDetailResponse {
    private UUID id;
    private String cameraCode;
    private String name;
    private CameraType cameraType;
    private CameraStatus status;
    private OperationalStatus operationalStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private CameraStreamConfigResponse streamConfig;
    private RoiGeometry roiGeometry;
    private AreaSimpleResponse assignedArea;
    private List<AreaSimpleResponse> assignedAreas;
    private Double mapLongitude;
    private Double mapLatitude;
    private String mapBuilding;
    private Integer mapFloorOrder;

    public List<AreaSimpleResponse> getAssignedAreas() {
        if (assignedAreas != null && !assignedAreas.isEmpty()) {
            return assignedAreas;
        }
        return assignedArea != null ? List.of(assignedArea) : List.of();
    }
}
