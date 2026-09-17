package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.RequestStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccessRequestReviewRequest(
    @NotNull(message = "Trạng thái phê duyệt không được để trống")
    RequestStatus status,

    @Size(max = 500, message = "Lý do từ chối tối đa 500 ký tự")
    String rejectionReason
) {
    @AssertTrue(message = "Lý do từ chối không được để trống khi từ chối yêu cầu")
    public boolean isRejectionReasonValid() {
        if (status == RequestStatus.REJECTED) {
            return rejectionReason != null && !rejectionReason.trim().isEmpty();
        }
        return true;
    }
}
