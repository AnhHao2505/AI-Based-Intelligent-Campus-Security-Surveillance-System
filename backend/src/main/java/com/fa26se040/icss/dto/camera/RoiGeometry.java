package com.fa26se040.icss.dto.camera;

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
public class RoiGeometry {

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    private List<RoiPolygon> polygons;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoiPolygon {
        private String label;

        @JsonProperty("alert_rules")
        private List<String> alertRules;

        private List<Vertex> vertices;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Vertex {
            private BigDecimal x;
            private BigDecimal y;
        }
    }
}
