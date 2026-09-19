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

    public static final Set<String> ALLOWED_ALERT_RULES = Set.of(
            "INTRUSION_DETECTION",
            "LOITERING_DETECTION",
            "UNAUTHORIZED_ACCESS"
    );

    public void validate(RoiGeometry geometry) {
        if (geometry == null || geometry.getPolygons() == null) {
            throw new CameraException(CameraErrorCode.ERR_ROI_001);
        }

        List<RoiGeometry.RoiPolygon> polygons = geometry.getPolygons();
        if (polygons.isEmpty() || polygons.size() > 10) {
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

        // V5 — Label length <= 100
        if (polygon.getLabel() != null && polygon.getLabel().length() > 100) {
            throw new CameraException(CameraErrorCode.ERR_ROI_005);
        }

        // Alert Rules validation
        if (polygon.getAlertRules() != null) {
            for (String rule : polygon.getAlertRules()) {
                if (rule == null || rule.isBlank() || !ALLOWED_ALERT_RULES.contains(rule.trim().toUpperCase())) {
                    throw new CameraException(CameraErrorCode.ERR_ROI_007);
                }
            }
        }

        // V2 — At least 3 vertices
        List<RoiGeometry.RoiPolygon.Vertex> vertices = polygon.getVertices();
        if (vertices == null || vertices.size() < 3) {
            throw new CameraException(CameraErrorCode.ERR_ROI_002);
        }

        // V3 — Every coordinate in [0.0, 1.0]
        for (RoiGeometry.RoiPolygon.Vertex v : vertices) {
            if (v == null || v.getX() == null || v.getY() == null) {
                throw new CameraException(CameraErrorCode.ERR_ROI_003);
            }
            if (v.getX().compareTo(BigDecimal.ZERO) < 0 || v.getX().compareTo(BigDecimal.ONE) > 0
                    || v.getY().compareTo(BigDecimal.ZERO) < 0 || v.getY().compareTo(BigDecimal.ONE) > 0) {
                throw new CameraException(CameraErrorCode.ERR_ROI_003);
            }
        }

        // V4 — At least 3 distinct vertices
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
}
