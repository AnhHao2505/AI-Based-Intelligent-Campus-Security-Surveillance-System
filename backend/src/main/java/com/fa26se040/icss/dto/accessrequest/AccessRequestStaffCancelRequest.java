package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;

/** BR-RQ-47: FM huỷ đơn đã duyệt chưa bắt đầu phải ghi lý do, độ dài theo REASON_MIN_LENGTH..REASON_MAX_LENGTH (tính sau khi trim). */
public record AccessRequestStaffCancelRequest(
    @NotBlank(message = "Lý do huỷ không được để trống")
    @ReasonLength(subject = "Lý do huỷ")
    String reason
) {
}
