package com.fa26se040.icss.dto.assignedpersonnel;

import com.fa26se040.icss.enums.Role;

import java.util.UUID;

public record AssignedPersonnelUserInfo(
    UUID id,
    String userCode,
    String fullName,
    Role role
) {}
