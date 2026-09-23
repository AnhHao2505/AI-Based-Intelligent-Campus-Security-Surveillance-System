package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaResponse(
    UUID id,
    String code,
    String name,
    AreaLevel areaLevel,
    Integer areaAccessLevel,
    Boolean explicitAuthorizationRequired,
    String building,
    String floor,
    String description,
    AreaGeometry geometry,
    Boolean isActive,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Boolean differsFromPreset
) {
    public AreaResponse(
        UUID id,
        String code,
        String name,
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        String building,
        String floor,
        String description,
        AreaGeometry geometry,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        this(id, code, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, description, geometry, isActive, createdAt, updatedAt, false);
    }

    public AreaResponse(
        UUID id,
        String code,
        String name,
        AreaLevel areaLevel,
        String building,
        String floor,
        String description,
        AreaGeometry geometry,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        this(id, code, name, areaLevel, null, null, building, floor, description, geometry, isActive, createdAt, updatedAt, false);
    }
}
