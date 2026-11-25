package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.enums.ShiftType;

import java.time.LocalDate;
import java.util.UUID;

public record GuardTeamDispatchAuditSnapshot(
        UUID id,
        UUID guardId,
        UUID fromTeamId,
        String fromTeamName,
        UUID toTeamId,
        String toTeamName,
        LocalDate startDate,
        LocalDate endDate,
        ShiftType shiftType,
        GuardDispatchStatus status,
        String reason
) implements AuditSnapshot {

    public static GuardTeamDispatchAuditSnapshot from(GuardTeamDispatch dispatch) {
        if (dispatch == null) {
            return null;
        }
        return new GuardTeamDispatchAuditSnapshot(
                dispatch.getId(),
                dispatch.getGuard() != null ? dispatch.getGuard().getId() : null,
                dispatch.getFromTeam() != null ? dispatch.getFromTeam().getId() : null,
                dispatch.getFromTeam() != null ? dispatch.getFromTeam().getTeamName() : null,
                dispatch.getToTeam() != null ? dispatch.getToTeam().getId() : null,
                dispatch.getToTeam() != null ? dispatch.getToTeam().getTeamName() : null,
                dispatch.getStartDate(),
                dispatch.getEndDate(),
                dispatch.getShiftType(),
                dispatch.getStatus(),
                dispatch.getReason()
        );
    }
}
