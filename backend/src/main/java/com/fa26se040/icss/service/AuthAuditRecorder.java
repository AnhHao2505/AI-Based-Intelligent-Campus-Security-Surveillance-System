package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AuthAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR-AUDIT (B3): audit module AUTH. LOGIN_FAILED và ACCOUNT_LOCKED ghi trong transaction RIÊNG (REQUIRES_NEW) để
 * không bị rollback theo phản hồi 401; LOGIN_SUCCESS / PASSWORD_RESET_* tham gia transaction của người gọi (nếu có).
 * Snapshot chỉ có e-mail đã chuẩn hoá, phương thức, lý do nội bộ, số phút khoá — không có mật khẩu, token, link.
 * Tách bean riêng để REQUIRES_NEW đi qua proxy (gọi nội bộ trong AuthService sẽ bỏ qua @Transactional).
 */
@Component
@RequiredArgsConstructor
public class AuthAuditRecorder {

    /** Lý do nội bộ của LOGIN_FAILED (chỉ ADMIN xem trong nhật ký; phản hồi ra ngoài luôn là câu chung BR-AU-32). */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    public static final String BAD_PASSWORD = "BAD_PASSWORD";
    public static final String DEACTIVATED = "DEACTIVATED";
    public static final String DELETED = "DELETED";
    public static final String LOCKED = "LOCKED";
    public static final String GOOGLE_NOT_REGISTERED = "GOOGLE_NOT_REGISTERED";
    public static final String GOOGLE_TOKEN_INVALID = "GOOGLE_TOKEN_INVALID";

    public static final String METHOD_PASSWORD = "PASSWORD";
    public static final String METHOD_GOOGLE = "GOOGLE";

    static final String SOURCE_LOGIN = "AUTH_LOGIN";
    static final String SOURCE_PASSWORD_RESET = "AUTH_PASSWORD_RESET";

    private final AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginFailed(String email, User account, String method, String reason) {
        auditService.record(AuditTargetType.AUTH, AuditAction.LOGIN_FAILED, targetId(account, email), null, account,
                null, new AuthAuditSnapshot(email, method, reason, null), null, AuditActor.system(SOURCE_LOGIN));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accountLocked(String email, User account, int lockMinutes) {
        auditService.record(AuditTargetType.AUTH, AuditAction.ACCOUNT_LOCKED, targetId(account, email), null, account,
                null, new AuthAuditSnapshot(email, METHOD_PASSWORD, null, lockMinutes), null, AuditActor.system(SOURCE_LOGIN));
    }

    @Transactional
    public void loginSuccess(String email, User account, String method) {
        auditService.record(AuditTargetType.AUTH, AuditAction.LOGIN_SUCCESS, account.getId().toString(), null, account,
                null, new AuthAuditSnapshot(email, method, null, null), null, AuditActor.user(account));
    }

    /** Chỉ gọi khi e-mail thuộc một tài khoản đang hoạt động; người gửi yêu cầu chưa đăng nhập -> actor SYSTEM. */
    @Transactional
    public void passwordResetRequested(String email, User account) {
        auditService.record(AuditTargetType.AUTH, AuditAction.PASSWORD_RESET_REQUESTED, account.getId().toString(), null,
                account, null, new AuthAuditSnapshot(email, null, null, null), null, AuditActor.system(SOURCE_PASSWORD_RESET));
    }

    /** Người giữ link hợp lệ = chủ tài khoản -> actor USER là chính tài khoản đó. */
    @Transactional
    public void passwordResetCompleted(User account) {
        auditService.record(AuditTargetType.AUTH, AuditAction.PASSWORD_RESET_COMPLETED, account.getId().toString(), null,
                account, null, new AuthAuditSnapshot(account.getEmail(), null, null, null), null, AuditActor.user(account));
    }

    private static String targetId(User account, String email) {
        if (account != null && account.getId() != null) {
            return account.getId().toString();
        }
        if (email == null || email.isBlank()) {
            return "UNKNOWN";
        }
        return email.length() <= 100 ? email : email.substring(0, 100);
    }
}
