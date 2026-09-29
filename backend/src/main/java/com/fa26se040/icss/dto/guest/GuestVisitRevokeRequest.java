package com.fa26se040.icss.dto.guest;

/** FM thu hồi lượt đã duyệt (BR-GV-13). Lý do bắt buộc 10–500. */
public record GuestVisitRevokeRequest(Long version, String reason) {
}
