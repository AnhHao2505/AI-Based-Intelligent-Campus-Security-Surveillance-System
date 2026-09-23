package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record AreaAccessRulesAuditSnapshot(
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired
) implements AccessControlAuditSnapshot {}
