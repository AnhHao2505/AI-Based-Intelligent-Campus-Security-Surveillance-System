package com.fa26se040.icss.dto.accesscontrol;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LevelPresetUpdateRequest(
        @NotNull(message = "Cấp độ truy cập khu vực không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        Integer areaAccessLevel,

        @NotNull(message = "Cờ yêu cầu chỉ định đích danh không được để trống")
        Boolean explicitAuthorizationRequired,

        @NotBlank(message = "Lý do không được để trống")
        @ReasonLength
        String reason,

        @NotNull(message = "Phiên bản dữ liệu không được để trống")
        Long version
) {}
