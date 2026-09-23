package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import java.time.OffsetDateTime;

public record AreaAssignmentAuditSnapshot(
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        AssignedPersonnelStatus status
) implements AccessControlAuditSnapshot {}
