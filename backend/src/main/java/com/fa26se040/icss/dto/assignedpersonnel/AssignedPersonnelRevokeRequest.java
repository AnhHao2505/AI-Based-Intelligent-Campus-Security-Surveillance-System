package com.fa26se040.icss.dto.assignedpersonnel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignedPersonnelRevokeRequest(
    @NotBlank(message = "Lý do không được để trống")
    @Size(min = 10, max = 500, message = "Lý do phải có từ 10 đến 500 ký tự")
    String reason
) {}
