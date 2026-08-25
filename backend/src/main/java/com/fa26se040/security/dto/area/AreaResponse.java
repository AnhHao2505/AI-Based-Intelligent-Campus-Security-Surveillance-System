package com.fa26se040.security.dto.area;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AreaResponse(
    Integer id,
    String code,
    String name,
    AreaLevelResponse level,
    String building,
    String floor,
    String description,
    BigDecimal mapX,
    BigDecimal mapY,
    Boolean isActive,
    Integer createdBy,
    OffsetDateTime createdAt,
    Integer updatedBy,
    OffsetDateTime updatedAt
) {}
