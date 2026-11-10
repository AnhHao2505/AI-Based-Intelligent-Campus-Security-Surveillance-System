package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AuthAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import org.springframework.beans.factory.annotation.Value;
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

    /** Độ dài tối thiểu của khoá HMAC (cùng ngưỡng khoá dịch vụ nội bộ). */
    static final int MIN_HASH_KEY_LENGTH = 32;

    private final AuditService auditService;
    private final byte[] hashKey;

    /**
     * H1: khoá HMAC đọc từ app.security.audit-hash-key (env APP_SECURITY_AUDITHASHKEY). Không có khoá mặc định:
     * thiếu hoặc ngắn hơn MIN_HASH_KEY_LENGTH -> backend không khởi động (profile test đặt khoá trong application-test.yml).
     */
    public AuthAuditRecorder(AuditService auditService, @Value("${app.security.audit-hash-key:}") String hashKey) {
        if (hashKey == null || hashKey.isBlank() || hashKey.length() < MIN_HASH_KEY_LENGTH) {
            // Không đưa giá trị khoá vào thông báo
            throw new IllegalStateException("Thiếu khoá băm audit: app.security.audit-hash-key (env APP_SECURITY_AUDITHASHKEY) "
                    + "chưa được đặt hoặc ngắn hơn " + MIN_HASH_KEY_LENGTH + " ký tự. Sinh khoá bằng: openssl rand -hex 32. "
                    + "Backend không khởi động khi thiếu khoá.");
        }
        this.auditService = auditService;
        this.hashKey = hashKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * X2: chuỗi người dùng gõ chỉ đi vào audit khi đã che / băm — xem {@link #identify(User, String)}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginFailed(String email, User account, String method, String reason) {
        Identity id = identify(account, email, hashKey);
        auditService.record(AuditTargetType.AUTH, AuditAction.LOGIN_FAILED, id.targetId(), null, account,
                null, new AuthAuditSnapshot(id.email(), method, reason, null, id.hash()), null, AuditActor.system(SOURCE_LOGIN));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accountLocked(String email, User account, int lockMinutes) {
        Identity id = identify(account, email, hashKey);
        auditService.record(AuditTargetType.AUTH, AuditAction.ACCOUNT_LOCKED, id.targetId(), null, account,
                null, new AuthAuditSnapshot(id.email(), METHOD_PASSWORD, null, lockMinutes, id.hash()), null,
                AuditActor.system(SOURCE_LOGIN));
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

    /** Đối tượng của dòng audit đăng nhập thất bại / khoá: target_id + e-mail được phép lưu + hash (nếu không khớp). */
    record Identity(String targetId, String email, String hash) {}

    static final String UNKNOWN_PREFIX = "UNKNOWN:";
    private static final java.util.regex.Pattern EMAIL_SHAPE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * X2 (audit-login-privacy):
     * <ul>
     *   <li>Khớp tài khoản (kể cả đã khoá / đã xoá): target_id = userId, e-mail = e-mail CỦA TÀI KHOẢN — không lưu chuỗi
     *       người dùng gõ.</li>
     *   <li>Không khớp: target_id = "UNKNOWN:" + 16 hex đầu HMAC-SHA256(khoá, chuỗi trim + lower) (H1: có khoá để không
     *       dò ngược được bằng cách băm thử danh sách e-mail); e-mail = chuỗi đã che
     *       (LogMasker.maskEmail khi có dạng e-mail đầy đủ a@b.c, ngược lại "***" — kể cả chuỗi có '@' nhưng không phải
     *       e-mail, vd mật khẩu gõ nhầm ô "MatKhau@123", để không lộ phần sau '@'). Cùng chuỗi -> cùng hash, để đếm lần thử.</li>
     *   <li>Không có chuỗi (token Google sai): target_id = "UNKNOWN", không e-mail, không hash.</li>
     * </ul>
     */
    static Identity identify(User account, String typed, byte[] key) {
        if (account != null && account.getId() != null) {
            return new Identity(account.getId().toString(), account.getEmail(), null);
        }
        String normalized = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return new Identity("UNKNOWN", null, null);
        }
        String hash = hmacPrefix(normalized, key);
        String masked = EMAIL_SHAPE.matcher(normalized).matches()
                ? com.fa26se040.icss.util.LogMasker.maskEmail(normalized)
                : "***";
        return new Identity(UNKNOWN_PREFIX + hash, masked, hash);
    }

    /** H1: 16 ký tự hex đầu của HMAC-SHA256(key, value). */
    static String hmacPrefix(String value, byte[] key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
