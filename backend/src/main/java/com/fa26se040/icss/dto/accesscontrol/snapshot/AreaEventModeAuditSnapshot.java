package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaEventModeAuditSnapshot(
        Boolean openToMembers,
        OffsetDateTime openUntil,
        String reasonCode,
        String reasonLabel,
        String note,
        UUID sessionId,
        OffsetDateTime plannedEnd,
        UUID scheduleId
) implements AuditSnapshot {
    public AreaEventModeAuditSnapshot(Boolean openToMembers, OffsetDateTime openUntil, String reasonCode, String reasonLabel, String note) {
        this(openToMembers, openUntil, reasonCode, reasonLabel, note, null, null, null);
    }

    public AreaEventModeAuditSnapshot(Boolean openToMembers, OffsetDateTime openUntil) {
        this(openToMembers, openUntil, null, null, null, null, null, null);
    }
}
