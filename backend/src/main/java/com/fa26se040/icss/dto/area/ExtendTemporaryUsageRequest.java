package com.fa26se040.icss.dto.area;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record ExtendTemporaryUsageRequest(
    @NotNull(message = "Thời gian kết thúc mới không được để trống")
    OffsetDateTime newEndTime,

    String reason
) {}
