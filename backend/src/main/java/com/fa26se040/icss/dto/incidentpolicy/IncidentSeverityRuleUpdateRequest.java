package com.fa26se040.icss.dto.incidentpolicy;

import com.fa26se040.icss.enums.IncidentSeverity;
import jakarta.validation.constraints.NotNull;

public record IncidentSeverityRuleUpdateRequest(
        @NotNull(message = "Mức độ không được để trống")
        IncidentSeverity severity,

        @NotNull(message = "Thiếu version của luật")
        Long version
) {}
