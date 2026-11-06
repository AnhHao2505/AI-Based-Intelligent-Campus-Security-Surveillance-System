package com.fa26se040.icss.dto.assignedpersonnel;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;
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
    String note,

    @NotBlank(message = "Lý do không được để trống")
    @ReasonLength
    String reason,

    // Tuỳ chọn: true = ô "Hết giờ hành chính" (Lucas 05/11) -> validTo = giờ đóng cửa của ngày bắt đầu (AI_AFTER_HOUR_START)
    Boolean untilClosingTime
) {
    public AssignedPersonnelCreateRequest(UUID userId, OffsetDateTime validFrom, OffsetDateTime validTo, String note) {
        this(userId, validFrom, validTo, note, null);
    }

    public AssignedPersonnelCreateRequest(UUID userId, OffsetDateTime validFrom, OffsetDateTime validTo, String note,
                                          String reason) {
        this(userId, validFrom, validTo, note, reason, null);
    }
}
