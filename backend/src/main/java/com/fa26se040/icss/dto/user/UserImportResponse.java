package com.fa26se040.icss.dto.user;

import java.util.List;

public record UserImportResponse(
    int totalProcessed,
    int successCount,
    int failedCount,
    List<UserImportRowError> errors
) {}
