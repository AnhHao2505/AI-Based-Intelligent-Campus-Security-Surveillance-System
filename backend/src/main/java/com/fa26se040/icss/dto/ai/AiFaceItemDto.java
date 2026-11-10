package com.fa26se040.icss.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFaceItemDto {
    private String code;
    private String fullName;
    private List<Float> embedding;
    private boolean isActive;
    private String role;
    private Long expiresAt;
}
