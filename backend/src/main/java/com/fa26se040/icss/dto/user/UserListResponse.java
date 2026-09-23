package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserListResponse(
    UUID id,
    String userCode,
    String fullName,
    String email,
    Role role,
    Boolean isActive,
    OffsetDateTime createdAt,
    UUID teamId,
    String teamName
) {
    public static UserListResponse fromEntity(User user) {
        boolean hasActiveTeam = user.getTeam() != null && Boolean.TRUE.equals(user.getTeam().getIsActive());
        return new UserListResponse(
            user.getId(),
            user.getUserCode(),
            user.getFullName(),
            user.getEmail(),
            user.getRole(),
            user.getIsActive(),
            user.getCreatedAt(),
            hasActiveTeam ? user.getTeam().getId() : null,
            hasActiveTeam ? user.getTeam().getTeamName() : null
        );
    }
}
