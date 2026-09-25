package com.fa26se040.icss.dto.guard;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardTeamMemberAssignRequest {

    @NotNull(message = "Danh sách ID bảo vệ không được null")
    private List<UUID> guardIds;

    private Integer weekdayMorningDemand;
    private Integer weekdayAfternoonDemand;
    private Integer weekdayNightDemand;
    private Integer sundayMorningDemand;
    private Integer sundayAfternoonDemand;
    private Integer sundayNightDemand;
    private Boolean hasSundayCustom;
}
