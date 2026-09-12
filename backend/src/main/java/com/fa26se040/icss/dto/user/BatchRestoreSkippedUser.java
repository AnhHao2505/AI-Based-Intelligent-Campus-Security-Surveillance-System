package com.fa26se040.icss.dto.user;

public record BatchRestoreSkippedUser(
    String userCode,
    String email,
    String reason
) {}
