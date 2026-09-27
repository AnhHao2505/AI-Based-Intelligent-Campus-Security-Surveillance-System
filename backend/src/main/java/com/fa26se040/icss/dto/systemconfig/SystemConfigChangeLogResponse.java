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
    String changedByName,
    String reason
) {
    public SystemConfigChangeLogResponse(UUID id, String configKey, String oldValue, String newValue, OffsetDateTime changedAt, String changedByEmail, String changedByName) {
        this(id, configKey, oldValue, newValue, changedAt, changedByEmail, changedByName, null);
    }
}
