package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RoiGeometryValidator {

    public void validate(RoiGeometry geometry) {
        if (geometry == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }

        List<RoiGeometry.RoiPolygon> polygons = geometry.getPolygons();

        boolean hasPolygons = polygons != null && !polygons.isEmpty();

        if (!hasPolygons) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }

        if (polygons.size() > 10) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }
        for (RoiGeometry.RoiPolygon polygon : polygons) {
            validatePolygon(polygon);
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
