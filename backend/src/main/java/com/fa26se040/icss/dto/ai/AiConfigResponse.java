package com.fa26se040.icss.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConfigResponse {

    private UUID id;
    private BigDecimal faceMatchThreshold;
    private Integer inferenceFps;
    private OffsetDateTime updatedAt;
}
