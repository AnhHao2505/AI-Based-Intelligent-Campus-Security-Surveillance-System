package com.fa26se040.icss.dto.accesscontrol;

import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessControlAuditLogResponse(
        UUID id,
        AccessControlTargetType targetType,
        AccessControlAction action,
        String targetId,
        UUID areaId,
        String areaName,
        String areaCode,
        UUID subjectUserId,
        String subjectUserName,
        String subjectUserCode,
        Object oldValue,
        Object newValue,
        String reason,
        UUID changedById,
        String changedByName,
        String changedByUserCode,
        OffsetDateTime changedAt
) {}
