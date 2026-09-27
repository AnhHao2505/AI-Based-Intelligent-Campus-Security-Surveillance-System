package com.fa26se040.icss.dto.reasoncatalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReasonCatalogResponse(
        UUID id,
        String actionType,
        String code,
        String label,
        Boolean isOther,
        Boolean isActive,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
