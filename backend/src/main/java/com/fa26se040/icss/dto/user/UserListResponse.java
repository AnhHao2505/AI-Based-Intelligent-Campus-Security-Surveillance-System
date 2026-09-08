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
    OffsetDateTime createdAt
) {
    public static UserListResponse fromEntity(User user) {
        return new UserListResponse(
            user.getId(),
            user.getUserCode(),
            user.getFullName(),
            user.getEmail(),
            user.getRole(),
            user.getIsActive(),
            user.getCreatedAt()
        );
    }
}
