package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardScheduleTemplateDto {
    private UUID id;
    private UUID guardId;
    private String guardName;
    private String guardCode;
    private Integer dayOfWeek;
    private ShiftType shiftType;
    private LocalTime startTime;
    private LocalTime endTime;
    private UUID areaId;
    private String areaName;
    private String building;
    private String radioChannel;
    private String notes;
    private Boolean isActive;
}
