package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.AreaLevel;
import java.util.UUID;

public record AreaSimpleResponse(
    UUID id,
    String code,
    String name,
    AreaLevel areaLevel,
    String building,
    String floor
) {}
