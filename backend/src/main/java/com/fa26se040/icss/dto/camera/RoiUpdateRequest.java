package com.fa26se040.icss.dto.camera;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("roi_geometry")
    @JsonAlias({"roi_geometry", "roiGeometry"})
    private RoiGeometry roiGeometry;

    @JsonProperty("snapshot_base64")
    @JsonAlias({"snapshot_base64", "snapshotBase64"})
    private String snapshotBase64;

    @JsonProperty("snapshot_width")
    @JsonAlias({"snapshot_width", "snapshotWidth"})
    private Integer snapshotWidth;

    @JsonProperty("snapshot_height")
    @JsonAlias({"snapshot_height", "snapshotHeight"})
    private Integer snapshotHeight;
}
