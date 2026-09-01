package com.fa26se040.security.service;

import com.fa26se040.security.dto.area.AreaGeometry;
import com.fa26se040.security.entity.Area;
import com.fa26se040.security.exception.AreaErrorCode;
import com.fa26se040.security.exception.AreaException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class AreaGeometryValidator {

    public void validate(AreaGeometry geometry, UUID currentAreaId, String building, String floor, List<Area> existingAreasOnFloor) {
        validateAreaLocation(building, floor);
        validateVertices(geometry);
        validateBoundingBoxOverlap(geometry, currentAreaId, existingAreasOnFloor);
    }

    public void validateAreaLocation(String building, String floor) {
        if (building == null || building.trim().isEmpty() || floor == null || floor.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_015);
        }
    }

    public void validateVertices(AreaGeometry geometry) {
        // V1 — At least 3 vertices (BR-01)
        if (geometry == null || geometry.getVertices() == null || geometry.getVertices().size() < 3) {
            throw new AreaException(AreaErrorCode.ERR_AREA_011);
        }

        // V2 — Every coordinate within [0, 1] (BR-05)
        for (AreaGeometry.Vertex vertex : geometry.getVertices()) {
            if (vertex == null || vertex.getX() == null || vertex.getY() == null) {
                throw new AreaException(AreaErrorCode.ERR_AREA_012);
            }
            if (vertex.getX().compareTo(BigDecimal.ZERO) < 0 || vertex.getX().compareTo(BigDecimal.ONE) > 0 ||
                vertex.getY().compareTo(BigDecimal.ZERO) < 0 || vertex.getY().compareTo(BigDecimal.ONE) > 0) {
                throw new AreaException(AreaErrorCode.ERR_AREA_012);
            }
        }

        // FIX-4 — At least 3 distinct vertices (non-degenerate polygon)
        java.util.List<AreaGeometry.Vertex> distinctVertices = new java.util.ArrayList<>();
        for (AreaGeometry.Vertex v : geometry.getVertices()) {
            boolean exists = distinctVertices.stream().anyMatch(existing ->
                    existing.getX().compareTo(v.getX()) == 0 && existing.getY().compareTo(v.getY()) == 0
            );
            if (!exists) {
                distinctVertices.add(v);
            }
        }
        if (distinctVertices.size() < 3) {
            throw new AreaException(AreaErrorCode.ERR_AREA_016);
        }

        // BR-02 (self-intersecting polygon) — not implemented in MVP.
        // Plug the check in here; input and output shape stay the same.
    }

    public void validateBoundingBoxOverlap(AreaGeometry geometry, UUID currentAreaId, List<Area> existingAreasOnFloor) {
        // BR-03 — currently a bounding-box approximation.
        // To upgrade to exact polygon intersection, replace only the body of this method.
        if (existingAreasOnFloor == null || existingAreasOnFloor.isEmpty()) {
            return;
        }

        BoundingBox currentBox = computeBoundingBox(geometry.getVertices());

        for (Area otherArea : existingAreasOnFloor) {
            if (otherArea == null) continue;
            if (currentAreaId != null && otherArea.getId() != null && otherArea.getId().equals(currentAreaId)) {
                continue;
            }
            if (otherArea.getDeletedAt() != null) {
                continue;
            }
            if (otherArea.getGeometry() == null || otherArea.getGeometry().getVertices() == null || otherArea.getGeometry().getVertices().size() < 3) {
                continue;
            }

            BoundingBox otherBox = computeBoundingBox(otherArea.getGeometry().getVertices());

            boolean noOverlap = currentBox.maxX.compareTo(otherBox.minX) <= 0
                    || otherBox.maxX.compareTo(currentBox.minX) <= 0
                    || currentBox.maxY.compareTo(otherBox.minY) <= 0
                    || otherBox.maxY.compareTo(currentBox.minY) <= 0;

            if (!noOverlap) {
                throw new AreaException(AreaErrorCode.ERR_AREA_013);
            }
        }
    }

    private BoundingBox computeBoundingBox(List<AreaGeometry.Vertex> vertices) {
        BigDecimal minX = vertices.get(0).getX();
        BigDecimal maxX = vertices.get(0).getX();
        BigDecimal minY = vertices.get(0).getY();
        BigDecimal maxY = vertices.get(0).getY();

        for (AreaGeometry.Vertex v : vertices) {
            if (v.getX().compareTo(minX) < 0) minX = v.getX();
            if (v.getX().compareTo(maxX) > 0) maxX = v.getX();
            if (v.getY().compareTo(minY) < 0) minY = v.getY();
            if (v.getY().compareTo(maxY) > 0) maxY = v.getY();
        }

        return new BoundingBox(minX, maxX, minY, maxY);
    }

    private record BoundingBox(BigDecimal minX, BigDecimal maxX, BigDecimal minY, BigDecimal maxY) {}
}
