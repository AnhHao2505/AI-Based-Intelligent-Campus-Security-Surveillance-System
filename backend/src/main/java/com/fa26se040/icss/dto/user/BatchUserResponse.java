package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BatchUserResponse(
    UUID id,
    String userCode,
    String fullName,
    String email,
    Role role,
    Boolean isActive,
    OffsetDateTime deletedAt,
    OffsetDateTime createdAt
) {
    public static BatchUserResponse fromEntity(User user) {
        return new BatchUserResponse(
            user.getId(),
            user.getUserCode(),
            user.getFullName(),
            user.getEmail(),
            user.getRole(),
            user.getIsActive(),
            user.getDeletedAt(),
            user.getCreatedAt()
        );
    }
}
