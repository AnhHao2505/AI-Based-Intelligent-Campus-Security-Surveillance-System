package com.fa26se040.icss.dto.accesscontrol.snapshot;

public record ReasonCatalogAuditSnapshot(
        String code,
        String label,
        String actionType,
        Boolean isOther,
        Boolean isActive,
        Integer sortOrder
) implements AccessControlAuditSnapshot {}
