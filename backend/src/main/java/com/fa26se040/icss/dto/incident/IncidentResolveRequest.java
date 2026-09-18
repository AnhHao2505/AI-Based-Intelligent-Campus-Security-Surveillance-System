package com.fa26se040.icss.dto.incident;

import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentResolveRequest {

    @NotNull(message = "Kết quả (outcome) không được để trống")
    private IncidentOutcome outcome;

    @NotNull(message = "Phân loại giải quyết (resolutionCategory) không được để trống")
    private IncidentResolutionCategory resolutionCategory;

    private String resolutionNotes;
    private String evidenceImageUrl;
}
