package com.fa26se040.security.dto.area;

import java.util.UUID;

public record AreaListItemResponse(
    UUID id,
    String code,
    String name,
    AreaLevelResponse level,
    String building,
    String floor,
    Boolean isActive
) {}
