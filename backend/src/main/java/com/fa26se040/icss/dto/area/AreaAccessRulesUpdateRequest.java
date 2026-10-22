package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AreaAccessRulesUpdateRequest(
        @NotNull(message = "Cấp độ truy cập khu vực không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập khu vực phải từ 1 đến 3")
        Integer areaAccessLevel,

        @NotNull(message = "Cờ yêu cầu chỉ định đích danh không được để trống")
        Boolean explicitAuthorizationRequired,

        @NotBlank(message = "Lý do không được để trống")
        @ReasonLength
        String reason,
        // Step 5b (BR-TC-13): version của khu vực mà client đang xem
        Long version
) {
    public AreaAccessRulesUpdateRequest(Integer areaAccessLevel, Boolean explicitAuthorizationRequired, String reason) {
        this(areaAccessLevel, explicitAuthorizationRequired, reason, null);
    }

    public AreaAccessRulesUpdateRequest(Integer areaAccessLevel, Boolean explicitAuthorizationRequired) {
        this(areaAccessLevel, explicitAuthorizationRequired, null, null);
    }
}
