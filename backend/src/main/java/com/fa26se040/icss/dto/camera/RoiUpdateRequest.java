package com.fa26se040.icss.dto.camera;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiUpdateRequest {

    @NotNull(message = "ROI geometry không được null")
    @Valid
    private RoiGeometry roiGeometry;
}
