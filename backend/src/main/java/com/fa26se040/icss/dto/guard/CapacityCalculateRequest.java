package com.fa26se040.icss.dto.guard;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacityCalculateRequest {

    @NotNull(message = "Nhu cầu Ca Sáng không được để trống")
    @Min(value = 0, message = "Nhu cầu Ca Sáng tối thiểu là 0")
    private Integer morningDemand;

    @NotNull(message = "Nhu cầu Ca Chiều không được để trống")
    @Min(value = 0, message = "Nhu cầu Ca Chiều tối thiểu là 0")
    private Integer afternoonDemand;

    @NotNull(message = "Nhu cầu Ca Đêm không được để trống")
    @Min(value = 0, message = "Nhu cầu Ca Đêm tối thiểu là 0")
    private Integer nightDemand;

    @Builder.Default
    private Boolean hasSundayCustom = true;

    private Integer sundayMorningDemand;

    private Integer sundayAfternoonDemand;

    private Integer sundayNightDemand;

    @Builder.Default
    private Boolean hasWeekendCustom = true;

    private Integer weekendMorningDemand;

    private Integer weekendAfternoonDemand;

    private Integer weekendNightDemand;
}
