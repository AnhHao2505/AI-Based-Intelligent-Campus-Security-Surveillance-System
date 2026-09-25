package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaResponse(
    UUID id,
    String name,
    AreaLevel areaLevel,
    Integer areaAccessLevel,
    Boolean explicitAuthorizationRequired,
    String building,
    String floor,
    AreaGeometry geometry,
    Boolean isActive,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Boolean differsFromPreset
) {
    public AreaResponse(
        UUID id,
        String name,
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        String building,
        String floor,
        AreaGeometry geometry,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, geometry, isActive, createdAt, updatedAt, false);
    }

    public AreaResponse(
        UUID id,
        String name,
        AreaLevel areaLevel,
        String building,
        String floor,
        AreaGeometry geometry,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        this(id, name, areaLevel, null, null, building, floor, geometry, isActive, createdAt, updatedAt, false);
    }
}
