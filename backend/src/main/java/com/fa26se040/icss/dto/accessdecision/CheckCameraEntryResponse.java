package com.fa26se040.icss.dto.accessdecision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Kết quả xét quyền ra vào tại vị trí Camera quan sát.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckCameraEntryResponse {
    private boolean allowed;
    private AccessSource source;
    /** Mốc thời gian hết hạn hiệu lực quyền (Epoch seconds, ví dụ 1700301780.0 cho 16:03:00). */
    private Double expiresAt;
    /** Mốc thời gian bắt đầu có hiệu lực quyền (Epoch seconds, ví dụ 1700308320.0 cho 17:52:00). */
    private Double startsAt;
    private String reason;
    /** Mã lý do từ chối (GuestEntryDenyReason) cho khách bị từ chối; null khi cho phép hoặc với người dùng nội bộ. */
    private String denyReason;
    private String cameraCode;
    private UUID areaId;
    private String areaName;

    @JsonIgnore
    private UUID userId;

    private String userCode;

    @JsonIgnore
    private String fullName;

    @JsonIgnore
    private Role userRole;

    @JsonIgnore
    private Integer userAccessLevel;

    @JsonIgnore
    private Boolean userKnownAbsent;

    public static CheckCameraEntryResponse denied(String cameraCode, String userCode, String reason) {
        String effectiveUserCode = (userCode != null && !userCode.isBlank()) ? userCode : "UNKNOWN";
        return CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason(reason)
                .cameraCode(cameraCode)
                .userCode(effectiveUserCode)
                .build();
    }
}
