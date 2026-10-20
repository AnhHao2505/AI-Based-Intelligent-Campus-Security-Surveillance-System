package com.fa26se040.icss.dto.assignedpersonnel;

import com.fa26se040.icss.validation.ReasonLength;
import jakarta.validation.constraints.NotBlank;

public record AssignedPersonnelRevokeRequest(
    @NotBlank(message = "Lý do không được để trống")
    @ReasonLength
    String reason
) {}
