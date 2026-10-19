package com.fa26se040.icss.dto.guest;

/** FM thu hồi lượt đã duyệt (BR-GV-13). Lý do bắt buộc, dài REASON_MIN_LENGTH..REASON_MAX_LENGTH. */
public record GuestVisitRevokeRequest(Long version, String reason) {
}
