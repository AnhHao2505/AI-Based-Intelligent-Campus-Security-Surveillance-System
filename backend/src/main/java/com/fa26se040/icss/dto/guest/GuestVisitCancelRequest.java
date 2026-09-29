package com.fa26se040.icss.dto.guest;

/** Host huỷ lượt (BR-GV-09). version = version lượt đang xem (BR-GV-32); lý do tuỳ chọn, có thì 10–500. */
public record GuestVisitCancelRequest(Long version, String reason) {
}
