package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaTemporaryUsageResponse(
    UUID id,
    UUID areaId,
    String eventName,
    String reason,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    UUID createdBy,
    OffsetDateTime createdAt,
    UUID updatedBy,
    OffsetDateTime updatedAt
) {}
