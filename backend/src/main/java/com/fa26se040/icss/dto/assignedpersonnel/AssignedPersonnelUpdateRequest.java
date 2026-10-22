package com.fa26se040.icss.dto.assignedpersonnel;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

/**
 * Chỉ cho phép đổi validTo. validTo = null nghĩa là chuyển thành không thời hạn.
 */
public record AssignedPersonnelUpdateRequest(
    OffsetDateTime validTo,

    @NotBlank(message = "Lý do không được để trống")
    @ReasonLength
    String reason
) {
    public AssignedPersonnelUpdateRequest(OffsetDateTime validTo) {
        this(validTo, null);
    }
}
