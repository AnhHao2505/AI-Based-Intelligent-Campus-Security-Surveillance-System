package com.fa26se040.icss.dto.guard;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WizardGenerateShiftsRequest {

    private UUID teamId;

    private String newTeamName;

    private List<UUID> memberGuardIds;

    private List<UUID> selectedGuardIds;

    @NotNull(message = "Ngày bắt đầu không được để trống")
    private LocalDate startDate;

    private LocalDate endDate;

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

    private String building;

    @Builder.Default
    private Boolean saveAsTemplate = true;
}
