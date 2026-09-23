package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.enums.ShiftType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GuardTeamDispatchDto(
        UUID id,
        UUID guardId,
        String guardFullName,
        String guardUserCode,
        String guardEmail,
        UUID fromTeamId,
        String fromTeamName,
        UUID toTeamId,
        String toTeamName,
        LocalDate startDate,
        LocalDate endDate,
        ShiftType shiftType,
        String reason,
        GuardDispatchStatus status,
        UUID createdById,
        String createdByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static GuardTeamDispatchDto fromEntity(GuardTeamDispatch d) {
        if (d == null) return null;
        return new GuardTeamDispatchDto(
                d.getId(),
                d.getGuard() != null ? d.getGuard().getId() : null,
                d.getGuard() != null ? d.getGuard().getFullName() : null,
                d.getGuard() != null ? d.getGuard().getUserCode() : null,
                d.getGuard() != null ? d.getGuard().getEmail() : null,
                d.getFromTeam() != null ? d.getFromTeam().getId() : null,
                d.getFromTeam() != null ? d.getFromTeam().getTeamName() : null,
                d.getToTeam() != null ? d.getToTeam().getId() : null,
                d.getToTeam() != null ? d.getToTeam().getTeamName() : null,
                d.getStartDate(),
                d.getEndDate(),
                d.getShiftType(),
                d.getReason(),
                d.getStatus(),
                d.getCreatedBy() != null ? d.getCreatedBy().getId() : null,
                d.getCreatedBy() != null ? d.getCreatedBy().getFullName() : null,
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
