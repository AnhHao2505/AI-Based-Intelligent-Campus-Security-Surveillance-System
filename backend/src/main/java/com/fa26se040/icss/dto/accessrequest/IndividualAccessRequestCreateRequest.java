package com.fa26se040.icss.dto.accessrequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.fa26se040.icss.validation.ReasonLength;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IndividualAccessRequestCreateRequest(
    @NotNull(message = "Khu vực không được để trống")
    UUID areaId,

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    OffsetDateTime startTime,

    @NotNull(message = "Thời gian kết thúc không được để trống")
    OffsetDateTime endTime,

    @NotBlank(message = "Mục đích không được để trống")
    @ReasonLength(subject = "Mục đích")
    String purpose
) {}
