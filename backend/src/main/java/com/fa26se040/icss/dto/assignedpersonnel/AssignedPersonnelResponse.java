package com.fa26se040.icss.dto.assignedpersonnel;

import com.fa26se040.icss.enums.AssignedPersonnelStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignedPersonnelResponse(
    UUID id,
    UUID areaId,
    AssignedPersonnelUserInfo user,
    OffsetDateTime validFrom,
    OffsetDateTime validTo,
    String note,
    AssignedPersonnelStatus status,
    String createdBy,
    OffsetDateTime createdAt,
    OffsetDateTime revokedAt,
    String revokedBy,
    String revokeReason
) {}
