package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.LocalDate;
import java.util.UUID;

public record GuardShiftBatchAuditSnapshot(
        String operation,
        UUID teamId,
        LocalDate startDate,
        LocalDate endDate,
        String building,
        int affectedCount,
        int clearedCount
) implements AuditSnapshot {
}
