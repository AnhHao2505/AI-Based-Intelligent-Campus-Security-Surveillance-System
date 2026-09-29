package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RoiGeometryValidator {

    public static final Set<String> ALLOWED_DIRECTIONS = Set.of(
            "AB_IS_IN",
            "AB_IS_OUT"
    );

    public void validate(RoiGeometry geometry) {
        if (geometry == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }

        List<RoiGeometry.RoiPolygon> polygons = geometry.getPolygons();
        List<RoiGeometry.EntryLine> entryLines = geometry.getEntryLines();

        boolean hasPolygons = polygons != null && !polygons.isEmpty();
        boolean hasLines = entryLines != null && !entryLines.isEmpty();

        if (!hasPolygons && !hasLines) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }

        if (hasPolygons) {
            if (polygons.size() > 10) {
                throw new CameraException(CameraErrorCode.ERR_ROI_001);
            }
            for (RoiGeometry.RoiPolygon polygon : polygons) {
                validatePolygon(polygon);
            }
        }

        if (hasLines) {
            if (entryLines.size() > 5) {
                throw new CameraException(CameraErrorCode.ERR_ROI_009);
            }
            for (RoiGeometry.EntryLine line : entryLines) {
                validateEntryLine(line);
            }
        }
    }

    private void validatePolygon(RoiGeometry.RoiPolygon polygon) {
        if (polygon == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_002);
        }

        if (polygon.getLabel() != null && polygon.getLabel().length() > 100) {
            throw new CameraException(CameraErrorCode.ERR_ROI_005);
        }

        List<RoiGeometry.RoiPolygon.Vertex> vertices = polygon.getVertices();
        if (vertices == null || vertices.size() < 3) {
            throw new CameraException(CameraErrorCode.ERR_ROI_002);
        }

        for (RoiGeometry.RoiPolygon.Vertex v : vertices) {
            validateCoordinate(v.getX(), v.getY());
        }

        List<RoiGeometry.RoiPolygon.Vertex> distinctVertices = new ArrayList<>();
        for (RoiGeometry.RoiPolygon.Vertex v : vertices) {
            boolean exists = distinctVertices.stream().anyMatch(existing ->
                    existing.getX().compareTo(v.getX()) == 0 && existing.getY().compareTo(v.getY()) == 0
            );
            if (!exists) {
                distinctVertices.add(v);
            }
        }

        if (distinctVertices.size() < 3) {
            throw new CameraException(CameraErrorCode.ERR_ROI_004);
        }
    }

    private void validateEntryLine(RoiGeometry.EntryLine line) {
        if (line == null || line.getPointA() == null || line.getPointB() == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_008);
        }

        if (line.getLabel() != null && line.getLabel().length() > 100) {
            throw new CameraException(CameraErrorCode.ERR_ROI_005);
        }

        validateCoordinate(line.getPointA().getX(), line.getPointA().getY());
        validateCoordinate(line.getPointB().getX(), line.getPointB().getY());

        double dx = line.getPointB().getX().doubleValue() - line.getPointA().getX().doubleValue();
        double dy = line.getPointB().getY().doubleValue() - line.getPointA().getY().doubleValue();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.001) {
            throw new CameraException(CameraErrorCode.ERR_ROI_008);
        }

        if (line.getDirection() == null || line.getDirection().isBlank()) {
            line.setDirection("AB_IS_IN");
        } else {
            String dir = line.getDirection().trim().toUpperCase();
            if (!ALLOWED_DIRECTIONS.contains(dir)) {
                throw new CameraException(CameraErrorCode.ERR_ROI_007);
            }
            line.setDirection(dir);
        }
    }

    private void validateCoordinate(BigDecimal x, BigDecimal y) {
        if (x == null || y == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_003);
        }
        if (x.compareTo(BigDecimal.ZERO) < 0 || x.compareTo(BigDecimal.ONE) > 0
                || y.compareTo(BigDecimal.ZERO) < 0 || y.compareTo(BigDecimal.ONE) > 0) {
            throw new CameraException(CameraErrorCode.ERR_ROI_003);
        }
    }
}
