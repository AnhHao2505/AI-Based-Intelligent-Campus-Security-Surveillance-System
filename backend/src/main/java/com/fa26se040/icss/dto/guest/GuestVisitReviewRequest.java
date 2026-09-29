package com.fa26se040.icss.dto.guest;

/** FM duyệt / từ chối (BR-GV-10, 11). decision = APPROVED | REJECTED; từ chối bắt buộc lý do 10–500. */
public record GuestVisitReviewRequest(Long version, String decision, String reason) {
}
