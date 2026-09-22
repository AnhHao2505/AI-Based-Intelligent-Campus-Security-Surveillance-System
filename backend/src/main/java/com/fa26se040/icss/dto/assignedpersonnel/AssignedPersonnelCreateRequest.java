package com.fa26se040.icss.dto.assignedpersonnel;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignedPersonnelCreateRequest(
    @NotNull(message = "Người dùng không được để trống")
    UUID userId,

    // Tuỳ chọn, bỏ trống = thời điểm hiện tại
    OffsetDateTime validFrom,

    // Tuỳ chọn, bỏ trống = không thời hạn
    OffsetDateTime validTo,

    @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    String note
) {}
