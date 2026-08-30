package com.fa26se040.security.dto.area;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateTemporaryUsageRequest(
    @NotBlank(message = "Tên sự kiện không được để trống")
    @Size(max = 150, message = "Tên sự kiện tối đa 150 ký tự")
    String eventName,

    @Size(max = 255, message = "Lý do tối đa 255 ký tự")
    String reason,

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    OffsetDateTime startTime,

    @NotNull(message = "Thời gian kết thúc không được để trống")
    OffsetDateTime endTime
) {}
