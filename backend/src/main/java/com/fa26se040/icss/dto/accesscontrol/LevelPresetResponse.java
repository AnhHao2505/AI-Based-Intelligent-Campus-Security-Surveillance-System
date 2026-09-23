package com.fa26se040.icss.dto.accesscontrol;

import com.fa26se040.icss.enums.AreaLevel;
import java.time.OffsetDateTime;

public record LevelPresetResponse(
        AreaLevel areaLevel,
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired,
        OffsetDateTime updatedAt,
        String updatedByName,
        String updatedByUserCode,
        Long version
) {}
