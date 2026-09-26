package com.fa26se040.icss.dto.accesscontrol.snapshot;

public sealed interface AccessControlAuditSnapshot permits
        AreaAssignmentAuditSnapshot,
        UserAccessLevelAuditSnapshot,
        AreaAccessRulesAuditSnapshot,
        LevelPresetAuditSnapshot,
        AreaEventModeAuditSnapshot {
}
