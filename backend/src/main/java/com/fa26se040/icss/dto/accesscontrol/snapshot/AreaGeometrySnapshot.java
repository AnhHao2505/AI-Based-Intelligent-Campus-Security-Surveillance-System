package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.dto.area.AreaGeometry;

import java.util.List;

public record AreaGeometrySnapshot(
        String type,
        Integer version,
        List<AreaGeometry.Vertex> coordinates
) implements AuditSnapshot {

    public static AreaGeometrySnapshot from(AreaGeometry geometry) {
        if (geometry == null) {
            return null;
        }
        return new AreaGeometrySnapshot(
                geometry.getType(),
                geometry.getVersion(),
                geometry.getVertices() != null ? List.copyOf(geometry.getVertices()) : List.of()
        );
    }
}
