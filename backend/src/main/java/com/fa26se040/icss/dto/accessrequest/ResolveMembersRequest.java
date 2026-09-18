package com.fa26se040.icss.dto.accessrequest;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ResolveMembersRequest(
    @NotEmpty(message = "Danh sách mã người dùng không được để trống")
    List<String> userCodes
) {}
