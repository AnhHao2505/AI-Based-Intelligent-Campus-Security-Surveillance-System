package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record DutyPostTypeAuditSnapshot(
        String code,
        String label,
        Boolean dispatchable,
        Boolean isActive,
        Integer sortOrder
) implements AuditSnapshot {}
