package com.fa26se040.icss.dto.floorplan;

import java.util.UUID;

public record FloorPlanResponse(
    UUID id,
    String building,
    String floor,
    String imageKey,
    Integer originalWidth,
    Integer originalHeight,
    Boolean isActive
) {}
