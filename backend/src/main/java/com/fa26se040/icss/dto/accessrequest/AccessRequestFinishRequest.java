package com.fa26se040.icss.dto.accessrequest;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/** BR-RQ-46: FM chuyển đơn sang Hoàn thành phải ghi lý do 10–500 ký tự (tính sau khi trim). */
public record AccessRequestFinishRequest(
    @NotBlank(message = "Lý do hoàn thành không được để trống")
    String reason
) {
    @AssertTrue(message = "Lý do hoàn thành phải có từ 10 đến 500 ký tự")
    public boolean isReasonLengthValid() {
        if (reason == null) {
            return true; // @NotBlank báo lỗi
        }
        int len = reason.trim().length();
        return len >= 10 && len <= 500;
    }
}
