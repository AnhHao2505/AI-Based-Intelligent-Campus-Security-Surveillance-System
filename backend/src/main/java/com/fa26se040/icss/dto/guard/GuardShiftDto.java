package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardShiftDto {
    private UUID id;
    private UUID guardId;
    private String guardName;
    private String guardCode;
    private LocalDate shiftDate;
    private ShiftType shiftType;
    private LocalTime startTime;
    private LocalTime endTime;
    private UUID areaId;
    private String areaName;
    private UUID buildingId;
    private String buildingName;
    private String building;
    private Boolean crossBuilding;
    private String crossBuildingReason;
    private ShiftStatus status;
    private OffsetDateTime checkInAt;
    private OffsetDateTime checkOutAt;
    private String notes;
    private Boolean isOvertime;
    // V90 (BR-DSP-13): mã vị trí trực của ca
    private String dutyPostCode;
    // P1b (4.4): nhãn hiển thị của vị trí trực
    private String dutyPostLabel;
}
