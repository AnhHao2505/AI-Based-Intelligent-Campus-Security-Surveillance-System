package com.fa26se040.icss.dto.camera;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    @JsonAlias({"reference_snapshot_url", "referenceSnapshotUrl"})
    private String referenceSnapshotUrl;

    @JsonProperty("reference_snapshot_width")
    @JsonAlias({"reference_snapshot_width", "referenceSnapshotWidth"})
    private Integer referenceSnapshotWidth;

    @JsonProperty("reference_snapshot_height")
    @JsonAlias({"reference_snapshot_height", "referenceSnapshotHeight"})
    private Integer referenceSnapshotHeight;

    @JsonProperty("reference_captured_at")
    @JsonAlias({"reference_captured_at", "referenceCapturedAt"})
    private OffsetDateTime referenceCapturedAt;

    private List<RoiPolygon> polygons;

    @JsonProperty("entry_lines")
    @JsonAlias({"entry_lines", "entryLines"})
    private List<EntryLine> entryLines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoiPolygon {
        private String label;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EntryLine {
        private String label;

        @JsonProperty("point_a")
        @JsonAlias({"point_a", "pointA"})
        private RoiPolygon.Vertex pointA;

        @JsonProperty("point_b")
        @JsonAlias({"point_b", "pointB"})
        private RoiPolygon.Vertex pointB;

        @Builder.Default
        private String direction = "AB_IS_IN";
    }
}
