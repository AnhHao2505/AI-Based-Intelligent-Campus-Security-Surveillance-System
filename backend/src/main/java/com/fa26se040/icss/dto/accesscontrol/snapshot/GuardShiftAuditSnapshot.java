package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record GuardShiftAuditSnapshot(
        UUID id,
        UUID guardId,
        LocalDate shiftDate,
        ShiftType shiftType,
        LocalTime startTime,
        LocalTime endTime,
        UUID areaId,
        UUID buildingId,
        Boolean isCrossBuilding,
        String crossBuildingReason,
        ShiftStatus status,
        Boolean isOvertime,
        String dutyPostCode,
        String notes
) implements AuditSnapshot {

    public static GuardShiftAuditSnapshot from(GuardShift shift) {
        if (shift == null) {
            return null;
        }
        return new GuardShiftAuditSnapshot(
                shift.getId(),
                shift.getGuard() != null ? shift.getGuard().getId() : null,
                shift.getShiftDate(),
                shift.getShiftType(),
                shift.getStartTime(),
                shift.getEndTime(),
                shift.getArea() != null ? shift.getArea().getId() : null,
                shift.getBuilding() != null ? shift.getBuilding().getId() : null,
                shift.getIsCrossBuilding(),
                shift.getCrossBuildingReason(),
                shift.getStatus(),
                shift.getIsOvertime(),
                shift.getDutyPostCode(),
                shift.getNotes()
        );
    }
}
