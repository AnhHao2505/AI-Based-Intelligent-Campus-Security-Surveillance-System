package com.fa26se040.icss.dto.accessdecision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fa26se040.icss.enums.AccessSource;
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
    private String reason;
    private String cameraCode;
    private UUID areaId;
    private String areaName;

    @JsonIgnore
    private UUID userId;

    private String userCode;

    @JsonIgnore
    private String fullName;

    public static CheckCameraEntryResponse denied(String cameraCode, String userCode, String reason) {
        return CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason(reason)
                .cameraCode(cameraCode)
                .userCode(userCode)
                .build();
    }
}
