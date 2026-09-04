package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import java.util.UUID;

public record AreaListItemResponse(
    UUID id,
    String code,
    String name,
    AreaLevel areaLevel,
    String building,
    String floor,
    Boolean isActive
) {}
