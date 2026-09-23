package com.fa26se040.icss.dto.guard;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardShiftRequestReviewDto {

    @JsonAlias({"targetSubstituteGuardId", "substituteId"})
    private UUID substituteGuardId;

    @JsonAlias({"reviewNote", "reason", "notes"})
    private String reviewNotes;
}
