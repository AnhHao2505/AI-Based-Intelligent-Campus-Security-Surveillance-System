package com.fa26se040.security.dto.area;

public record AreaLevelResponse(
    Short level,
    String code,
    String name,
    Boolean requiresFaceRecognition,
    String description
) {}
