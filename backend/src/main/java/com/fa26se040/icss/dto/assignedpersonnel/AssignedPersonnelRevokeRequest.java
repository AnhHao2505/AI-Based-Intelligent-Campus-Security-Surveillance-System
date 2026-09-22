package com.fa26se040.icss.dto.assignedpersonnel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignedPersonnelRevokeRequest(
    @NotBlank(message = "Lý do thu hồi không được để trống")
    @Size(max = 500, message = "Lý do thu hồi tối đa 500 ký tự")
    String reason
) {}
