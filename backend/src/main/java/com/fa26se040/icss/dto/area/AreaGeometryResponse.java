package com.fa26se040.icss.dto.area;

import java.util.UUID;

public record AreaGeometryResponse(
    UUID id,
    String code,
    String name,
    Short level,
    Boolean isActive,
    AreaGeometry geometry
) {}
