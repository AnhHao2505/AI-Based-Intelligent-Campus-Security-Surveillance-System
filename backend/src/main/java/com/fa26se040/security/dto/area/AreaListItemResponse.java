package com.fa26se040.security.dto.area;

public record AreaListItemResponse(
    Integer id,
    String code,
    String name,
    AreaLevelResponse level,
    String building,
    String floor,
    Boolean isActive
) {}
