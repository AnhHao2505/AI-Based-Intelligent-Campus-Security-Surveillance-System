package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SecurityIncidentAuditSnapshot(
        UUID id,
        String cameraCode,
        String eventType,
        IncidentSeverity severity,
        IncidentStatus status,
        IncidentDispatchStage dispatchStage,
        String subjectUserCode,
        UUID claimedById,
        String claimedByName,
        OffsetDateTime claimedAt,
        UUID resolvedById,
        String resolvedByName,
        OffsetDateTime resolvedAt,
        IncidentOutcome outcome,
        IncidentResolutionCategory resolutionCategory,
        String resolutionNotes
) implements AuditSnapshot {

    public static SecurityIncidentAuditSnapshot from(SecurityIncident incident) {
        if (incident == null) {
            return null;
        }
        return new SecurityIncidentAuditSnapshot(
                incident.getId(),
                incident.getCameraCode(),
                incident.getEventType(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getDispatchStage(),
                incident.getSubjectUserCode(),
                incident.getClaimedBy() != null ? incident.getClaimedBy().getId() : null,
                incident.getClaimedBy() != null ? incident.getClaimedBy().getFullName() : null,
                incident.getClaimedAt(),
                incident.getResolvedBy() != null ? incident.getResolvedBy().getId() : null,
                incident.getResolvedBy() != null ? incident.getResolvedBy().getFullName() : null,
                incident.getResolvedAt(),
                incident.getOutcome(),
                incident.getResolutionCategory(),
                incident.getResolutionNotes()
        );
    }

    public SecurityIncidentAuditSnapshot withDispatchStage(IncidentDispatchStage nextStage) {
        return new SecurityIncidentAuditSnapshot(
                id,
                cameraCode,
                eventType,
                severity,
                status,
                nextStage,
                subjectUserCode,
                claimedById,
                claimedByName,
                claimedAt,
                resolvedById,
                resolvedByName,
                resolvedAt,
                outcome,
                resolutionCategory,
                resolutionNotes
        );
    }
}
