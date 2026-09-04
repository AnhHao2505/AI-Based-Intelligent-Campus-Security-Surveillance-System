package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AreaCreateRequest(
    @NotBlank(message = "Mã khu vực không được để trống")
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9-]{1,48}[a-zA-Z0-9]$", message = "Mã khu vực chỉ gồm chữ cái, số và dấu gạch ngang, dài 3–50 ký tự")
    String code,

    @NotBlank(message = "Tên khu vực không được để trống")
    @Size(max = 150, message = "Tên khu vực tối đa 150 ký tự")
    String name,

    @NotNull(message = "Cấp độ khu vực không được để trống")
    AreaLevel areaLevel,

    @Size(max = 50, message = "Tên toà nhà tối đa 50 ký tự")
    String building,

    @Size(max = 20, message = "Tầng tối đa 20 ký tự")
    String floor,

    String description
) {}
