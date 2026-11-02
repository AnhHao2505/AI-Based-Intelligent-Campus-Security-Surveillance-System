package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record IncidentDispatchPolicyAuditSnapshot(
        String severity,
        Integer step1Size,
        Integer step1TimeoutSeconds,
        Integer escalateFmMinutes,
        Boolean notifyFmImmediately
) implements AuditSnapshot {}
