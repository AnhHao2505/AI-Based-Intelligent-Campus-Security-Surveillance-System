package com.fa26se040.icss.dto.systemconfig;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;

public record SystemConfigUpdateRequest(
    @NotBlank(message = "Giá trị cấu hình không được để trống")
    String configValue,

    @NotBlank(message = "Lý do không được để trống")
    @ReasonLength
    String reason
) {}
