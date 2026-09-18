package com.fa26se040.icss.dto.incident;

import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
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
    private UUID areaId;
    private String areaName;
    private String building;
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
}
