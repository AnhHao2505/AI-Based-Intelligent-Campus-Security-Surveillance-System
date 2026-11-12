package com.fa26se040.icss.dto.edge;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeHeartbeatDto {

    @JsonProperty("edge_id")
    private String edgeId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("ts")
    private String ts;

    @JsonProperty("cameras")
    private List<Map<String, Object>> cameras;

    @JsonProperty("gpu")
    private Boolean gpu;

    @JsonProperty("version")
    private String version;
}
