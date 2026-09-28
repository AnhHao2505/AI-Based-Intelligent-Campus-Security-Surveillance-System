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
    Double centerLatitude,
    Double centerLongitude,
    Boolean openToMembers,
    OffsetDateTime openUntil,
    Boolean eventActive,
    OffsetDateTime eventStartedAt,
    String eventStartedByName,
    OffsetDateTime eventLastAdjustedAt,
    String eventLastAdjustedByName,
    Integer upcomingScheduleCount,
    // Step 5b (BR-TC-13): version của khu vực (optimistic concurrency)
    Long version
) {
    /** Chữ ký canonical của main (trước Step 5b): version = null. */
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
        Boolean differsFromPreset,
        Double centerLatitude,
        Double centerLongitude,
        Boolean openToMembers,
        OffsetDateTime openUntil,
        Boolean eventActive,
        OffsetDateTime eventStartedAt,
        String eventStartedByName,
        OffsetDateTime eventLastAdjustedAt,
        String eventLastAdjustedByName,
        Integer upcomingScheduleCount
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor, isActive, geometry, hasGeometry, differsFromPreset, centerLatitude, centerLongitude, openToMembers, openUntil, eventActive, eventStartedAt, eventStartedByName, eventLastAdjustedAt, eventLastAdjustedByName, upcomingScheduleCount, null);
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
        Boolean hasGeometry,
        Boolean differsFromPreset,
        Boolean openToMembers,
        OffsetDateTime openUntil,
        Boolean eventActive,
        OffsetDateTime eventStartedAt,
        String eventStartedByName,
        OffsetDateTime eventLastAdjustedAt,
        String eventLastAdjustedByName
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor,
                isActive, geometry, hasGeometry, differsFromPreset, null, null, openToMembers,
                openUntil, eventActive, eventStartedAt, eventStartedByName, eventLastAdjustedAt,
                eventLastAdjustedByName, 0);
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
        Boolean hasGeometry,
        Boolean differsFromPreset,
        Boolean openToMembers,
        OffsetDateTime openUntil,
        Boolean eventActive
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor,
                isActive, geometry, hasGeometry, differsFromPreset, null, null, openToMembers,
                openUntil, eventActive, null, null, null, null, 0);
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
        Boolean hasGeometry,
        Boolean differsFromPreset
    ) {
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor,
                isActive, geometry, hasGeometry, differsFromPreset, null, null, false, null, false,
                null, null, null, null, 0);
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
        this(id, name, areaLevel, areaAccessLevel, explicitAuthorizationRequired, building, floor,
                isActive, geometry, hasGeometry, false, null, null, false, null, false,
                null, null, null, null, 0);
    }
}
