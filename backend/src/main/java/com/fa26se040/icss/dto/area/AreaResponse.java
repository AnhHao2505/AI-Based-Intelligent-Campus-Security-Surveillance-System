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
    Boolean differsFromPreset,
    Boolean openToMembers,
    OffsetDateTime openUntil,
    Boolean eventActive,
    OffsetDateTime eventStartedAt,
    String eventStartedByName,
    OffsetDateTime eventLastAdjustedAt,
    String eventLastAdjustedByName,
    Integer upcomingScheduleCount
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
        OffsetDateTime updatedAt,
        Boolean differsFromPreset,
        Boolean openToMembers,
        OffsetDateTime openUntil,
        Boolean eventActive,
        OffsetDateTime eventStartedAt,
        String eventStartedByName,
        OffsetDateTime eventLastAdjustedAt,
        String eventLastAdjustedByName
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, geometry, isActive, createdAt, updatedAt, differsFromPreset, openToMembers, openUntil, eventActive, eventStartedAt, eventStartedByName, eventLastAdjustedAt, eventLastAdjustedByName, 0);
    }

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
        OffsetDateTime updatedAt,
        Boolean differsFromPreset,
        Boolean openToMembers,
        OffsetDateTime openUntil,
        Boolean eventActive
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, geometry, isActive, createdAt, updatedAt, differsFromPreset, openToMembers, openUntil, eventActive, null, null, null, null, 0);
    }

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
        OffsetDateTime updatedAt,
        Boolean differsFromPreset
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, geometry, isActive, createdAt, updatedAt, differsFromPreset, false, null, false);
    }

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
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, geometry, isActive, createdAt, updatedAt, false, false, null, false);
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
        this(id, name, areaLevel, null, null, building, floor, geometry, isActive, createdAt, updatedAt, false, false, null, false);
    }
}
