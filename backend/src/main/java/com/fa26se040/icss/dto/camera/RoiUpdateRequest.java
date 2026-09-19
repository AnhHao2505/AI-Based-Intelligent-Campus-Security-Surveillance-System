package com.fa26se040.icss.dto.camera;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoiUpdateRequest {

    @NotNull(message = "ROI geometry không được null")
    @Valid
    private RoiGeometry roiGeometry;

    @com.fasterxml.jackson.annotation.JsonProperty("snapshot_base64")
    private String snapshotBase64;

    @com.fasterxml.jackson.annotation.JsonProperty("snapshot_width")
    private Integer snapshotWidth;

    @com.fasterxml.jackson.annotation.JsonProperty("snapshot_height")
    private Integer snapshotHeight;
}
