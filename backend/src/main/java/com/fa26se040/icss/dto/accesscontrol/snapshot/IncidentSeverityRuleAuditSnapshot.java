package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record IncidentSeverityRuleAuditSnapshot(
        String eventType,
        String areaLevelCode,
        String timeWindow,
        String severity
) implements AuditSnapshot {}
