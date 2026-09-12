package com.fa26se040.icss.dto.user;

import java.util.List;
import java.util.UUID;

public record BatchRestoreResponse(
    UUID importBatchId,
    int restoredCount,
    int skippedCount,
    List<BatchRestoreSkippedUser> skippedUsers
) {}
