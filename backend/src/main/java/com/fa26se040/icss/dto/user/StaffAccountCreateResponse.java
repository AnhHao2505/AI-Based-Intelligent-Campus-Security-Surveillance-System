package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffAccountCreateResponse {

    private UUID id;
    private String fullName;
    private String userCode;
    private String email;
    private Role role;
    private Boolean isActive;
    private FaceDataInfo faceData;
    private Instant createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FaceDataInfo {
        private UUID id;
        private String imageFrontUrl;
    }
}
