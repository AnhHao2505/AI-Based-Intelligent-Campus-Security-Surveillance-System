package com.fa26se040.icss.dto.guest;

/**
 * N1 (V120): độ dài mục đích lượt khách đang hiệu lực (GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH), đo sau khi trim.
 * Trả qua GET /api/system-configurations/guest-purpose-limits cho người mời khách (NORMAL_USER, FACILITY_MANAGER).
 */
public record GuestPurposeLimits(int minLength, int maxLength) {
}
