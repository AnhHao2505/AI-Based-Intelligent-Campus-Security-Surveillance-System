package com.fa26se040.icss.dto.camera;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoiGeometry {

    @JsonProperty("reference_snapshot_url")
    private String referenceSnapshotUrl;

    @JsonProperty("reference_snapshot_width")
    private Integer referenceSnapshotWidth;

    @JsonProperty("reference_snapshot_height")
    private Integer referenceSnapshotHeight;

    @JsonProperty("reference_captured_at")
    private OffsetDateTime referenceCapturedAt;

    private List<RoiPolygon> polygons;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoiPolygon {
        private String label;

        @JsonProperty("alert_rules")
        private List<String> alertRules;

        private List<Vertex> vertices;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Vertex {
            private BigDecimal x;
            private BigDecimal y;
        }
    }
}
