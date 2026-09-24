package com.fa26se040.icss.dto.guard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardTeamCreateRequest {

    @NotBlank(message = "Tên tổ đội không được để trống")
    @Size(max = 100, message = "Tên tổ đội tối đa 100 ký tự")
    private String teamName;

    private String description;

    private Integer weekdayMorningDemand;
    private Integer weekdayAfternoonDemand;
    private Integer weekdayNightDemand;
    private Integer sundayMorningDemand;
    private Integer sundayAfternoonDemand;
    private Integer sundayNightDemand;
    private Boolean hasSundayCustom;
}
