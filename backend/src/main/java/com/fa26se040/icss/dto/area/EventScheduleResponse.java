package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EventScheduleResponse(
        UUID id,
        UUID areaId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String status,
        String reasonCode,
        String reasonLabel,
        String note,
        UUID createdById,
        String createdByName,
        OffsetDateTime createdAt,
        String updatedByName,
        OffsetDateTime updatedAt,
        String cancelledByName,
        OffsetDateTime cancelledAt,
        String cancelReasonCode,
        String cancelReasonLabel,
        String cancelNote,
        OffsetDateTime failedAt,
        String failReason,
        UUID sessionId
) {
}
