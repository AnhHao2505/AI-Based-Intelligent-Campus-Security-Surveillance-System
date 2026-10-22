package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserAccessLevelUpdateRequest(
        @NotNull(message = "Cấp độ truy cập không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập phải từ 1 đến 3")
        Integer accessLevel,

        @NotBlank(message = "Lý do không được để trống")
        @ReasonLength
        String reason
) {
    public UserAccessLevelUpdateRequest(Integer accessLevel) {
        this(accessLevel, null);
    }
}
