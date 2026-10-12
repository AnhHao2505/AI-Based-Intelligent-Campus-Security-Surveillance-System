package com.fa26se040.icss.dto.accessrequest;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/** BR-RQ-47: FM huỷ đơn đã duyệt chưa bắt đầu phải ghi lý do 10–500 ký tự (tính sau khi trim). */
public record AccessRequestStaffCancelRequest(
    @NotBlank(message = "Lý do huỷ không được để trống")
    String reason
) {
    @AssertTrue(message = "Lý do huỷ phải có từ 10 đến 500 ký tự")
    public boolean isReasonLengthValid() {
        if (reason == null) {
            return true; // @NotBlank báo lỗi
        }
        int len = reason.trim().length();
        return len >= 10 && len <= 500;
    }
}
