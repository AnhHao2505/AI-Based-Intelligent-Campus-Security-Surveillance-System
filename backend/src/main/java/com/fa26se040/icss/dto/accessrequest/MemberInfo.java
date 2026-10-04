package com.fa26se040.icss.dto.accessrequest;

import java.util.UUID;

public record MemberInfo(
    UUID userId,
    String userCode,
    String fullName,
    Boolean sponsored
) {
    public MemberInfo(UUID userId, String userCode, String fullName) {
        this(userId, userCode, fullName, false);
    }
}
