package com.fa26se040.icss.dto.accesscontrol.snapshot;

/**
 * PR-AUDIT: audit AUTH (đăng nhập, khoá tạm, đặt lại mật khẩu). email = e-mail người dùng nhập đã chuẩn hoá
 * (trim + lower); method = PASSWORD / GOOGLE; failureReason = lý do thật nội bộ của LOGIN_FAILED (không trả ra ngoài).
 * Không có mật khẩu, token, link đặt lại mật khẩu.
 */
public record AuthAuditSnapshot(
        String email,
        String method,
        String failureReason,
        Integer lockMinutes
) implements AuditSnapshot {}
