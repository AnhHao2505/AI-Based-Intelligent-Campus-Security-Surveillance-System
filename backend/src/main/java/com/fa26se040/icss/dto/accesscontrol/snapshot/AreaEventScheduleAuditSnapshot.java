package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaEventScheduleAuditSnapshot(
        UUID scheduleId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String status,
        String reasonCode,
        String reasonLabel,
        String note,
        String failReason
) implements AuditSnapshot {
    public AreaEventScheduleAuditSnapshot(
            UUID scheduleId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            String reasonCode,
            String reasonLabel,
            String note
    ) {
        this(scheduleId, startAt, endAt, status, reasonCode, reasonLabel, note, null);
    }
}
