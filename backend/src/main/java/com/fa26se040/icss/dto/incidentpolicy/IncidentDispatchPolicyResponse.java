package com.fa26se040.icss.dto.incidentpolicy;

import com.fa26se040.icss.enums.IncidentSeverity;

import java.time.OffsetDateTime;

public record IncidentDispatchPolicyResponse(
        IncidentSeverity severity,
        Integer step1Size,
        Integer step1TimeoutSeconds,
        Integer escalateFmMinutes,
        Boolean notifyFmImmediately,
        Long version,
        OffsetDateTime updatedAt,
        String updatedByName
) {}
