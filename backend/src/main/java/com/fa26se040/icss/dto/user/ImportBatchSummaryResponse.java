package com.fa26se040.icss.dto.user;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImportBatchSummaryResponse(
    UUID importBatchId,
    Long activeCount,
    Long deletedCount,
    OffsetDateTime createdAt
) {
    public ImportBatchSummaryResponse(UUID importBatchId, long activeCount, long deletedCount, OffsetDateTime createdAt) {
        this(importBatchId, Long.valueOf(activeCount), Long.valueOf(deletedCount), createdAt);
    }
}
