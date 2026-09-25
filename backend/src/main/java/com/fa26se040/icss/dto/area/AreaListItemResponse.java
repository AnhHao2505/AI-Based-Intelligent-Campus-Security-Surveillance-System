package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
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
    Boolean differsFromPreset
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
        Boolean hasGeometry
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, isActive, geometry, hasGeometry, false);
    }
}
