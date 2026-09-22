package com.fa26se040.icss.dto.assignedpersonnel;

import java.time.OffsetDateTime;

/**
 * Chỉ cho phép đổi validTo. validTo = null nghĩa là chuyển thành không thời hạn.
 */
public record AssignedPersonnelUpdateRequest(
    OffsetDateTime validTo
) {}
