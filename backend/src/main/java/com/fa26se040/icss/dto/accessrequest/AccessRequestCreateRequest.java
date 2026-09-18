package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.RequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    @Size(max = 1000, message = "Mục đích tối đa 1000 ký tự")
    String purpose,

    List<String> memberUserCodes
) {}
