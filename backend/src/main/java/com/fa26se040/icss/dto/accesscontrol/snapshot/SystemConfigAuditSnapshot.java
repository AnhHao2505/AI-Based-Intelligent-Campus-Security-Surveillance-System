package com.fa26se040.icss.dto.accesscontrol.snapshot;

/** PR-AUDIT: audit SYSTEM_CONFIG / UPDATE — khoá và giá trị (cũ ở old_value, mới ở new_value). */
public record SystemConfigAuditSnapshot(
        String configKey,
        String value
) implements AuditSnapshot {}
