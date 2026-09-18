package com.fa26se040.icss.dto.systemconfig;

import jakarta.validation.constraints.NotBlank;

public record SystemConfigUpdateRequest(
    @NotBlank(message = "Giá trị cấu hình không được để trống")
    String configValue
) {}
