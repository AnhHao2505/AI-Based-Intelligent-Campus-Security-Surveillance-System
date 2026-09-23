package com.fa26se040.icss.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserAccessLevelUpdateRequest(
        @NotNull(message = "Cấp độ truy cập không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập phải từ 1 đến 3")
        Integer accessLevel,

        @NotBlank(message = "Lý do cập nhật không được để trống")
        @Size(max = 500, message = "Lý do cập nhật tối đa 500 ký tự")
        String reason
) {
    public UserAccessLevelUpdateRequest(Integer accessLevel) {
        this(accessLevel, null);
    }
}
