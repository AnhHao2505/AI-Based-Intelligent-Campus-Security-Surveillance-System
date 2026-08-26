package com.fa26se040.security.dto.area;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaResponse(
    UUID id,
    String code,
    String name,
    AreaLevelResponse level,
    String building,
    String floor,
    String description,
    BigDecimal mapX,
    BigDecimal mapY,
    Boolean isActive,
    UUID createdBy,
    OffsetDateTime createdAt,
    UUID updatedBy,
    OffsetDateTime updatedAt
) {}
