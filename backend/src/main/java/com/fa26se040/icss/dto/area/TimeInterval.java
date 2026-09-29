package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;

public record TimeInterval(
        OffsetDateTime start,
        OffsetDateTime end
) {
}
