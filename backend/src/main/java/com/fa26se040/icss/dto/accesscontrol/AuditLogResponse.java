package com.fa26se040.icss.dto.accesscontrol;

import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        AuditTargetType targetType,
        AuditAction action,
        String module,
        String targetId,
        UUID areaId,
        String areaName,
        UUID subjectUserId,
        String subjectUserName,
        String subjectUserCode,
        Object oldValue,
        Object newValue,
        String reason,
        String actorType,
        UUID changedById,
        String changedByName,
        String changedByUserCode,
        String actorSource,
        UUID correlationId,
        OffsetDateTime changedAt,
        // PR-AUDIT (V112): IP và user agent của request gây ra thao tác (null với job hệ thống, dòng cũ)
        String ipAddress,
        String userAgent
) {

    /** Chữ ký cũ (trước V112), không có IP / user agent. */
    public AuditLogResponse(UUID id, AuditTargetType targetType, AuditAction action, String module, String targetId,
                            UUID areaId, String areaName, UUID subjectUserId, String subjectUserName, String subjectUserCode,
                            Object oldValue, Object newValue, String reason, String actorType, UUID changedById,
                            String changedByName, String changedByUserCode, String actorSource, UUID correlationId,
                            OffsetDateTime changedAt) {
        this(id, targetType, action, module, targetId, areaId, areaName, subjectUserId, subjectUserName, subjectUserCode,
                oldValue, newValue, reason, actorType, changedById, changedByName, changedByUserCode, actorSource,
                correlationId, changedAt, null, null);
    }
}
