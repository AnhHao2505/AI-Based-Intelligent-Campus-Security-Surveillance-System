package com.fa26se040.icss.dto.assignedpersonnel;

import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Chỉ cho phép đổi validTo. validTo = null nghĩa là chuyển thành không thời hạn.
 */
public record AssignedPersonnelUpdateRequest(
    OffsetDateTime validTo,

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    String reason
) {
    public AssignedPersonnelUpdateRequest(OffsetDateTime validTo) {
        this(validTo, null);
    }
}
