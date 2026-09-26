package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.OffsetDateTime;

public record AreaEventModeAuditSnapshot(
        Boolean openToMembers,
        OffsetDateTime openUntil,
        String reasonCode,
        String reasonLabel,
        String note
) implements AccessControlAuditSnapshot {
    public AreaEventModeAuditSnapshot(Boolean openToMembers, OffsetDateTime openUntil) {
        this(openToMembers, openUntil, null, null, null);
    }
}
