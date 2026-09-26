package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;

public record AreaEventModeUpdateRequest(
        Boolean enabled,
        OffsetDateTime openUntil,
        String reason
) {}
