package com.fa26se040.icss.dto.incidentpolicy;

import jakarta.validation.constraints.NotNull;

/** Khoảng hợp lệ kiểm ở service (IncidentDispatchPolicy.*_MIN / *_MAX) để câu lỗi nội suy đúng giá trị. */
public record IncidentDispatchPolicyUpdateRequest(
        @NotNull(message = "Số bảo vệ mời ở đợt 1 không được để trống")
        Integer step1Size,

        @NotNull(message = "Thời gian chờ đợt 1 không được để trống")
        Integer step1TimeoutSeconds,

        @NotNull(message = "Thời gian chờ trước khi báo Quản lý CSVC không được để trống")
        Integer escalateFmMinutes,

        @NotNull(message = "Lựa chọn báo Quản lý CSVC ngay không được để trống")
        Boolean notifyFmImmediately,

        @NotNull(message = "Thiếu version của chính sách")
        Long version
) {}
