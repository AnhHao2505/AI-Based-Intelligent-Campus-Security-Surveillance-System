package com.fa26se040.icss.dto.incidentpolicy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DutyPostTypeUpdateRequest(
        @NotBlank(message = "Tên vị trí trực không được để trống")
        @Size(max = 100, message = "Tên vị trí trực tối đa 100 ký tự")
        String label,

        @NotNull(message = "Lựa chọn được mời ở đợt 1 không được để trống")
        Boolean dispatchable,

        @NotNull(message = "Trạng thái sử dụng không được để trống")
        Boolean isActive,

        Integer sortOrder,

        @NotNull(message = "Thiếu version của vị trí trực")
        Long version
) {}
