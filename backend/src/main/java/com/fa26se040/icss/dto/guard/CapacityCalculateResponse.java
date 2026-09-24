package com.fa26se040.icss.dto.guard;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacityCalculateResponse {
    private Integer dailyTotalShifts;
    private Integer weeklyTotalShifts;
    private Integer recommendedHeadcount;
    private Double averageShiftsPerGuard;
    private Double restDaysPerGuard;
}
