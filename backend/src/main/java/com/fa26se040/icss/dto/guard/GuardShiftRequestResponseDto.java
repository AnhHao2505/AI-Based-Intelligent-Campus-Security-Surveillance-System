package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import com.fa26se040.icss.enums.GuardShiftRequestType;
import com.fa26se040.icss.enums.ShiftType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardShiftRequestResponseDto {
    private UUID id;

    // Requester
    private UUID requesterId;
    private String requesterName;
    private String requesterCode;
    private String requesterTeamName;

    // Shift info (Shift A)
    private UUID shiftId;
    private LocalDate shiftDate;
    private ShiftType shiftType;
    private LocalTime startTime;
    private LocalTime endTime;
    private String areaName;

    // Request details
    private GuardShiftRequestType requestType;
    private Boolean isEmergency;
    private UUID substituteGuardId;
    private String substituteGuardName;
    private String substituteGuardCode;
    private UUID targetShiftId;

    // Target Shift Info (Shift B - for 2-way swap)
    private LocalDate targetShiftDate;
    private ShiftType targetShiftType;
    private LocalTime targetStartTime;
    private LocalTime targetEndTime;
    private String targetAreaName;

    private String reason;
    private GuardShiftRequestStatus status;

    // Reviewer details
    private UUID reviewedById;
    private String reviewedByName;
    private OffsetDateTime reviewedAt;
    private String reviewNotes;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
