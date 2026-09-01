package com.fa26se040.security.dto.area;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaGeometry {
    private String type;        // always "polygon" for now
    private Integer version;    // always 1 for now
    private List<Vertex> vertices;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Vertex {
        private BigDecimal x;   // normalized 0..1
        private BigDecimal y;   // normalized 0..1
    }
}
