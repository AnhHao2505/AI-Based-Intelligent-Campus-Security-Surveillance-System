package com.fa26se040.icss.dto.systemconfig;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SystemConfigResponse(
    String configKey,
    String configValue,
    String dataType,
    BigDecimal minValue,
    BigDecimal maxValue,
    String unit,
    String configGroup,
    Integer displayOrder,
    String description,
    Boolean editable,
    OffsetDateTime updatedAt,
    String updatedByEmail,
    String updatedByName
) {}
