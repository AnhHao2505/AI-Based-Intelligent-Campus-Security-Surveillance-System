package com.fa26se040.icss.dto.user;

import java.util.UUID;

public record BatchDeleteResponse(
    UUID importBatchId,
    int deletedCount,
    String message
) {}
