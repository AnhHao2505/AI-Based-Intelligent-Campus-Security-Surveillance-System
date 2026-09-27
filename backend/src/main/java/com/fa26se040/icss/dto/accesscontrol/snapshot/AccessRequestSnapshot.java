package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.AccessRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessRequestSnapshot(
        UUID id,
        String requestType,
        String status,
        UUID areaId,
        UUID requesterId,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        String reason,
        String rejectionReason
) implements AuditSnapshot {

    public static AccessRequestSnapshot from(AccessRequest request) {
        if (request == null) {
            return null;
        }
        return new AccessRequestSnapshot(
                request.getId(),
                request.getRequestType() != null ? request.getRequestType().name() : null,
                request.getStatus() != null ? request.getStatus().name() : null,
                request.getArea() != null ? request.getArea().getId() : null,
                request.getRequester() != null ? request.getRequester().getId() : null,
                request.getStartTime(),
                request.getEndTime(),
                request.getPurpose(),
                request.getRejectionReason()
        );
    }
}
