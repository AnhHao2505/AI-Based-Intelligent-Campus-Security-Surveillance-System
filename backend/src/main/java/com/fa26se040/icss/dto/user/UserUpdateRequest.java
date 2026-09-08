package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 100, message = "Họ và tên không được vượt quá 100 ký tự")
    String fullName,

    @NotNull(message = "Vai trò không được để trống")
    Role role
) {}
