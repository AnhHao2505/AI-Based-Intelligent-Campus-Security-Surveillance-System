package com.fa26se040.icss.dto.accesscontrol;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LevelPresetUpdateRequest(
        @NotNull(message = "Cấp độ truy cập khu vực không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        Integer areaAccessLevel,

        @NotNull(message = "Cờ yêu cầu chỉ định đích danh không được để trống")
        Boolean explicitAuthorizationRequired,

        @NotBlank(message = "Lý do cập nhật không được để trống")
        @Size(max = 500, message = "Lý do cập nhật tối đa 500 ký tự")
        String reason,

        @NotNull(message = "Phiên bản dữ liệu không được để trống")
        Long version
) {}
