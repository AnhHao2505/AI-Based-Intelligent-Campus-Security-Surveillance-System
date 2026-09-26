package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AreaListItemResponse(
    UUID id,
    String name,
    AreaLevel areaLevel,
    Integer areaAccessLevel,
    Boolean explicitAuthorizationRequired,
    String building,
    String floor,
    Boolean isActive,
    AreaGeometry geometry,
    Boolean hasGeometry,
    Boolean differsFromPreset,
    Boolean openToMembers,
    OffsetDateTime openUntil,
    Boolean eventActive
) {
    public AreaListItemResponse(
        UUID id,
        String name,
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        String building,
        String floor,
        Boolean isActive,
        AreaGeometry geometry,
        Boolean hasGeometry,
        Boolean differsFromPreset
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, isActive, geometry, hasGeometry, differsFromPreset, false, null, false);
    }

    public AreaListItemResponse(
        UUID id,
        String name,
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        String building,
        String floor,
        Boolean isActive,
        AreaGeometry geometry,
        Boolean hasGeometry
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, isActive, geometry, hasGeometry, false, false, null, false);
    }
}
