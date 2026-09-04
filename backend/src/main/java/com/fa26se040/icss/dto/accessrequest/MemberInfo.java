package com.fa26se040.icss.dto.accessrequest;

import java.util.UUID;

public record MemberInfo(
    UUID userId,
    String userCode,
    String fullName,
    String email
) {}
