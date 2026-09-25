package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.GuardShiftRequestType;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardShiftRequestCreateDto {

    @NotNull(message = "ID ca trực không được để trống")
    private UUID shiftId;

    @NotNull(message = "Loại yêu cầu không được để trống")
    private GuardShiftRequestType requestType;

    @JsonAlias({"targetSubstituteGuardId", "substituteId"})
    private UUID substituteGuardId;

    private UUID targetShiftId;

    private String reason;
}
