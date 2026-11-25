package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import com.fa26se040.icss.enums.GuardShiftRequestType;
import com.fa26se040.icss.enums.ShiftType;

import java.time.LocalDate;
import java.util.UUID;

public record GuardShiftRequestAuditSnapshot(
        UUID id,
        UUID requesterId,
        UUID shiftId,
        GuardShiftRequestType requestType,
        UUID substituteGuardId,
        UUID targetShiftId,
        LocalDate shiftDate,
        ShiftType shiftType,
        GuardShiftRequestStatus status,
        String reason,
        String reviewNotes
) implements AuditSnapshot {

    public static GuardShiftRequestAuditSnapshot from(GuardShiftRequest request) {
        if (request == null) {
            return null;
        }
        GuardShift shift = request.getShift();
        LocalDate date = shift != null ? shift.getShiftDate() : request.getShiftDateSnapshot();
        ShiftType type = null;
        if (shift != null) {
            type = shift.getShiftType();
        } else if (request.getShiftTypeSnapshot() != null) {
            try {
                type = ShiftType.valueOf(request.getShiftTypeSnapshot());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new GuardShiftRequestAuditSnapshot(
                request.getId(),
                request.getRequester() != null ? request.getRequester().getId() : null,
                shift != null ? shift.getId() : null,
                request.getRequestType(),
                request.getSubstituteGuard() != null ? request.getSubstituteGuard().getId() : null,
                request.getTargetShift() != null ? request.getTargetShift().getId() : null,
                date,
                type,
                request.getStatus(),
                request.getReason(),
                request.getReviewNotes()
        );
    }
}
