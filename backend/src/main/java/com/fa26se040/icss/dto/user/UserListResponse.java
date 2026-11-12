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
    String teamName,
    // LT-03: thời điểm hết khoá đăng nhập tạm (V114) nếu tài khoản ĐANG bị khoá; null nếu không khoá / người gọi không phải ADMIN
    OffsetDateTime loginLockedUntil
) {
    public static UserListResponse fromEntity(User user) {
        return fromEntity(user, null);
    }

    /**
     * LT-03: {@code now != null} thì kèm login_locked_until khi còn sau now (danh sách tài khoản của ADMIN);
     * {@code now == null} thì không kèm.
     */
    public static UserListResponse fromEntity(User user, OffsetDateTime now) {
        boolean hasActiveTeam = user.getTeam() != null && Boolean.TRUE.equals(user.getTeam().getIsActive());
        OffsetDateTime lockedUntil = user.getLoginLockedUntil();
        boolean locked = now != null && lockedUntil != null && lockedUntil.isAfter(now);
        return new UserListResponse(
            user.getId(),
            user.getUserCode(),
            user.getFullName(),
            user.getEmail(),
            user.getRole(),
            user.getIsActive(),
            user.getCreatedAt(),
            hasActiveTeam ? user.getTeam().getId() : null,
            hasActiveTeam ? user.getTeam().getTeamName() : null,
            locked ? lockedUntil : null
        );
    }
}
