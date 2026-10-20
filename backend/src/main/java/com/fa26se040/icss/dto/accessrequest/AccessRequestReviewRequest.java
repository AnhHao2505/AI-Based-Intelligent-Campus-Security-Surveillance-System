package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.validation.ValidRejectionReason;
import jakarta.validation.constraints.NotNull;

/**
 * MF3: FM duyệt / từ chối theo cả nhóm. Từ chối bắt buộc có lý do, kiểm chéo ở DTO qua {@link ValidRejectionReason}
 * (độ dài theo REASON_MIN_LENGTH..REASON_MAX_LENGTH, tính sau khi trim).
 */
@ValidRejectionReason
public record AccessRequestReviewRequest(
    @NotNull(message = "Trạng thái phê duyệt không được để trống")
    RequestStatus status,
    String rejectionReason
) {
}
