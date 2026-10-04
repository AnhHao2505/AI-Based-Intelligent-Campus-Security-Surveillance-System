package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record AreaListItemResponse(
    UUID id,
    String name,
    AreaLevel areaLevel,
    Integer areaAccessLevel,
    Boolean explicitAuthorizationRequired,
    String building,
    String floor,
    Boolean isActive,
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
}
