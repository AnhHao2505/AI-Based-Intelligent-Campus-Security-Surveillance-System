package com.fa26se040.icss.dto.incident;

import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentDetailResponse {
    private UUID id;
    private String eventId;
    private String cameraCode;
    private String cameraName;
    private CameraType cameraType;
    private UUID areaId;
    private String areaName;
    private String building;
    private String floor;
    private Double mapLatitude;
    private Double mapLongitude;
    private Integer mapFloorOrder;
    private String eventType;
    private String imageUrl;
    private OffsetDateTime detectedAt;
    private IncidentStatus status;
    private UUID claimedById;
    private String claimedByName;
    private OffsetDateTime claimedAt;
    private UUID resolvedById;
    private String resolvedByName;
    private OffsetDateTime resolvedAt;
    private IncidentOutcome outcome;
    private IncidentResolutionCategory resolutionCategory;
    private String resolutionNotes;
    private String evidenceImageUrl;
    private String radioChannel;
    // V90 (BR-SEV-01): null với sự cố tạo trước V90; client dùng bảng cũ theo eventType khi null
    private IncidentSeverity severity;
    // V93 (K6): STEP1 = đang mời một nhóm bảo vệ, chỉ người được mời nhận được; BROADCAST = như trước PR-2
    private IncidentDispatchStage dispatchStage;
    private Integer repeatCount;
    private String verificationStatus;
    private OffsetDateTime frameCapturedAt;
    private OffsetDateTime mqttPublishedAt;
    private OffsetDateTime mqttReceivedAt;
    private OffsetDateTime wsPushedAt;
}
