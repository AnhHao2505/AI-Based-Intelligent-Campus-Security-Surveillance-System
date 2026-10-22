package com.fa26se040.icss.dto.accessdecision;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * DTO yêu cầu xét quyền ra vào tại vị trí Camera quan sát.
 * Hỗ trợ nhận diện sự kiện di chuyển qua vùng ROI (VÀO / RA).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckCameraEntryRequest {

    @NotBlank(message = "Mã camera không được để trống")
    private String cameraCode;

    @NotBlank(message = "Mã người dùng không được để trống")
    private String userCode;

    private OffsetDateTime timestamp;

    private String roiLabel;

    private String eventDirection; // "ENTRY" | "EXIT"

    private Double durationSeconds;
}
