package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record UserAccessLevelAuditSnapshot(
        Integer accessLevel
) implements AccessControlAuditSnapshot {}
