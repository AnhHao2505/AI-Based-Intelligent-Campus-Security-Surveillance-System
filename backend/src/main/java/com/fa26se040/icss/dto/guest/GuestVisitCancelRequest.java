package com.fa26se040.icss.dto.guest;

/** Host huỷ lượt (BR-GV-09). version = version lượt đang xem (BR-GV-32); lý do tuỳ chọn, có thì dài REASON_MIN_LENGTH..REASON_MAX_LENGTH. */
public record GuestVisitCancelRequest(Long version, String reason) {
}
