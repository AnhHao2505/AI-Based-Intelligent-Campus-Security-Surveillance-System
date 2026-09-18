package com.fa26se040.icss.dto.accessrequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record GroupAccessRequestCreateRequest(
    @NotNull(message = "Khu vực không được để trống")
    UUID areaId,

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    OffsetDateTime startTime,

    @NotNull(message = "Thời gian kết thúc không được để trống")
    OffsetDateTime endTime,

    @NotBlank(message = "Mục đích không được để trống")
    @Size(max = 1000, message = "Mục đích tối đa 1000 ký tự")
    String purpose,

    @NotNull(message = "Danh sách thành viên không được để trống")
    @NotEmpty(message = "Danh sách thành viên không được để trống")
    List<String> memberUserCodes
) {}
