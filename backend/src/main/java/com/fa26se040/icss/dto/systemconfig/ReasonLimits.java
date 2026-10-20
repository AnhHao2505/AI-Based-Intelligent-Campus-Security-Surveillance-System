package com.fa26se040.icss.dto.systemconfig;

/**
 * Giới hạn độ dài lý do thao tác đang hiệu lực (REASON_MIN_LENGTH / REASON_MAX_LENGTH, V82), đo sau khi trim.
 * Trả qua GET /api/system-configurations/reason-limits cho mọi tài khoản đăng nhập để FE kiểm trước khi gửi.
 */
public record ReasonLimits(int minLength, int maxLength) {
}
