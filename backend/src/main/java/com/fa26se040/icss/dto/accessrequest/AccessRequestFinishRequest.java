package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;

/** BR-RQ-46: FM chuyển đơn sang Hoàn thành phải ghi lý do, độ dài theo REASON_MIN_LENGTH..REASON_MAX_LENGTH (tính sau khi trim). */
public record AccessRequestFinishRequest(
    @NotBlank(message = "Lý do hoàn thành không được để trống")
    @ReasonLength(subject = "Lý do hoàn thành")
    String reason
) {
}
