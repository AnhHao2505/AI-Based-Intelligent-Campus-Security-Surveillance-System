package com.fa26se040.icss.dto.accessrequest;

public record MemberLookupResult(
    String userCode,
    String fullName,
    boolean found,
    String reason
) {}
