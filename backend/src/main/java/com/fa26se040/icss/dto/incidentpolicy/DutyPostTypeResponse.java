package com.fa26se040.icss.dto.incidentpolicy;

import java.time.OffsetDateTime;

public record DutyPostTypeResponse(
        String code,
        String label,
        Boolean dispatchable,
        Boolean isActive,
        Integer sortOrder,
        Long version,
        OffsetDateTime updatedAt,
        String updatedByName
) {}
