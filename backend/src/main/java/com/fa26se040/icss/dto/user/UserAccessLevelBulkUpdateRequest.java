package com.fa26se040.icss.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** BR-AL-28: FM đổi cấp truy cập cho nhiều người. Ràng buộc cấp / lý do giống UserAccessLevelUpdateRequest. */
public record UserAccessLevelBulkUpdateRequest(
        @NotEmpty(message = "Chưa chọn người dùng nào")
        List<UUID> userIds,

        @NotNull(message = "Cấp độ truy cập không được để trống")
        @Min(value = 1, message = "Cấp độ truy cập phải từ 1 đến 3")
        @Max(value = 3, message = "Cấp độ truy cập phải từ 1 đến 3")
        Integer accessLevel,

        @NotBlank(message = "Lý do không được để trống")
        @Size(min = 10, max = 500, message = "Lý do phải có từ 10 đến 500 ký tự")
        String reason
) {}
