package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record LevelPresetAuditSnapshot(
        Integer areaAccessLevel,
        Boolean explicitAuthorizationRequired
) implements AccessControlAuditSnapshot {}
