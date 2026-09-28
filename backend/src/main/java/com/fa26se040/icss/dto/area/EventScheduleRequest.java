package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;

public record EventScheduleRequest(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String reasonCode,
        String note
) {
}
