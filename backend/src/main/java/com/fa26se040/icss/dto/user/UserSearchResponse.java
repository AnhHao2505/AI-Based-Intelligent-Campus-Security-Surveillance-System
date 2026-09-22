package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.enums.Role;

import java.util.UUID;

public record UserSearchResponse(
        UUID id,
        String userCode,
        String fullName,
        Role role,
        Integer accessLevel
) {}
