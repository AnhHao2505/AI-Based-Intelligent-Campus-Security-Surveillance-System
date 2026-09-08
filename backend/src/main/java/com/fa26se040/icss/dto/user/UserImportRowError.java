package com.fa26se040.icss.dto.user;

public record UserImportRowError(
    int row,
    String userCode,
    String message
) {}
