package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailableSwapShiftDto {
    private UUID targetShiftId;
    private UUID guardId;
    private String userCode;
    private String fullName;
    private String email;
    private String teamName;
    private LocalDate shiftDate;
    private ShiftType shiftType;
    private String shiftTypeName;
    private LocalTime startTime;
    private LocalTime endTime;
    private String areaName;
    private String building;
}
