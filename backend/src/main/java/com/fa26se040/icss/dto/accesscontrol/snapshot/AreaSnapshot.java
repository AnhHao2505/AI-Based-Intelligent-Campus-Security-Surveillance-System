package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;

import java.util.UUID;

public record AreaSnapshot(
        UUID id,
        String name,
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        String building,
        String floor,
        Boolean isActive
) implements AuditSnapshot {

    public static AreaSnapshot from(Area area) {
        if (area == null) {
            return null;
        }
        return new AreaSnapshot(
                area.getId(),
                area.getName(),
                area.getAreaLevel(),
                area.getAreaAccessLevel(),
                area.getExplicitAuthorizationRequired(),
                area.getBuilding(),
                area.getFloor(),
                area.getIsActive()
        );
    }
}
