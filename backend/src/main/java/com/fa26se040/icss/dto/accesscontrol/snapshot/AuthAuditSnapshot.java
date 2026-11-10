package com.fa26se040.icss.dto.accesscontrol.snapshot;

/**
 * PR-AUDIT: audit AUTH (đăng nhập, khoá tạm, đặt lại mật khẩu). method = PASSWORD / GOOGLE; failureReason = lý do thật
 * nội bộ của LOGIN_FAILED (không trả ra ngoài). Không có mật khẩu, token, link đặt lại mật khẩu.
 * X2 (audit-login-privacy): LOGIN_FAILED / ACCOUNT_LOCKED — khớp tài khoản: email = e-mail của tài khoản, emailHash null;
 * không khớp: email = chuỗi đã che, emailHash = 16 ký tự hex đầu SHA-256 của chuỗi đã chuẩn hoá (không lưu chuỗi gốc).
 */
public record AuthAuditSnapshot(
        String email,
        String method,
        String failureReason,
        Integer lockMinutes,
        String emailHash
) implements AuditSnapshot {

    public AuthAuditSnapshot(String email, String method, String failureReason, Integer lockMinutes) {
        this(email, method, failureReason, lockMinutes, null);
    }
}
