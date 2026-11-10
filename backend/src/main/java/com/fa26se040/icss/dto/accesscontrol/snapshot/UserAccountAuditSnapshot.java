package com.fa26se040.icss.dto.accesscontrol.snapshot;

/**
 * PR-AUDIT: trạng thái tài khoản trong audit USER_ACCOUNT. Không có mật khẩu, hash, e-mail.
 * Ba trường hệ quả chỉ có ở snapshot mới của DEACTIVATE / DELETE (null ở trường hợp khác).
 */
public record UserAccountAuditSnapshot(
        String userCode,
        String role,
        Integer accessLevel,
        Boolean isActive,
        Boolean deleted,
        Boolean hasFaceData,
        Integer cancelledRequests,
        Integer revokedAssignments,
        Integer websocketSessionsToClose
) implements AuditSnapshot {

    public static UserAccountAuditSnapshot of(String userCode, String role, Integer accessLevel, Boolean isActive,
                                              Boolean deleted, Boolean hasFaceData) {
        return new UserAccountAuditSnapshot(userCode, role, accessLevel, isActive, deleted, hasFaceData, null, null, null);
    }

    public UserAccountAuditSnapshot withEffects(int cancelledRequests, int revokedAssignments, int websocketSessionsToClose) {
        return new UserAccountAuditSnapshot(userCode, role, accessLevel, isActive, deleted, hasFaceData,
                cancelledRequests, revokedAssignments, websocketSessionsToClose);
    }
}
