package com.fa26se040.icss.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceDataResponseDto {
    private UUID id;
    private UUID userId;
    private String userCode;
    private String userName;
    private String code;
    private String fullName;
    private String imageFrontUrl;
    private Instant createdAt;
}
