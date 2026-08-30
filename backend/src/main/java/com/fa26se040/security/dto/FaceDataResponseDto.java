package com.fa26se040.security.dto;

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
    private String code;
    private String fullName;
    private String imageFrontUrl;
    private String imageLeftUrl;
    private String imageRightUrl;
    private Instant createdAt;
}
