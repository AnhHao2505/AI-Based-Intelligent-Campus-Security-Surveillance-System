package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.RequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.fa26se040.icss.validation.ReasonLength;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AccessRequestCreateRequest(
    @NotNull(message = "Khu vực không được để trống")
    UUID areaId,

    @NotNull(message = "Loại yêu cầu không được để trống")
    RequestType requestType,

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    OffsetDateTime startTime,

    @NotNull(message = "Thời gian kết thúc không được để trống")
    OffsetDateTime endTime,

    @NotBlank(message = "Mục đích không được để trống")
    @ReasonLength(subject = "Mục đích")
    String purpose,

    List<String> memberUserCodes
) {}
