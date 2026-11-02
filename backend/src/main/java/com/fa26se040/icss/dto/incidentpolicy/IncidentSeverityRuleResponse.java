package com.fa26se040.icss.dto.incidentpolicy;

import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentTimeWindow;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IncidentSeverityRuleResponse(
        UUID id,
        String eventType,
        AreaLevel areaLevelCode,
        IncidentTimeWindow timeWindow,
        IncidentSeverity severity,
        Long version,
        OffsetDateTime updatedAt,
        String updatedByName
) {}
