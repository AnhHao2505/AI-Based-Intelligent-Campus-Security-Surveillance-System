package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.OffsetDateTime;

public record AreaEventModeAuditSnapshot(
        Boolean openToMembers,
        OffsetDateTime openUntil
) implements AccessControlAuditSnapshot {}
