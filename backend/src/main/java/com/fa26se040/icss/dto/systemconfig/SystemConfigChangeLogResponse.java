package com.fa26se040.icss.dto.systemconfig;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemConfigChangeLogResponse(
    UUID id,
    String configKey,
    String oldValue,
    String newValue,
    OffsetDateTime changedAt,
    String changedByEmail,
    String changedByName
) {}
