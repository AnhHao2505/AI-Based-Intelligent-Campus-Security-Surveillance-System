package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RoiGeometryValidator {

    private final AreaRepository areaRepository;

    public static final Set<String> ALLOWED_ALERT_RULES = Set.of(
            "ENTRY_EXIT_TRACKING",
            "LOITERING",
            "CROWD_OVERCROWDING"
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

        // Target Area validation (ERR_ROI_006)
        if (polygon.getTargetAreaId() != null) {
            if (!areaRepository.existsByIdAndDeletedAtIsNull(polygon.getTargetAreaId())) {
                throw new CameraException(CameraErrorCode.ERR_ROI_006);
            }
        }

        // Alert Rules validation (ERR_ROI_007) - only 3 rules: ENTRY_EXIT_TRACKING, LOITERING, CROWD_OVERCROWDING
        if (polygon.getAlertRules() == null || polygon.getAlertRules().isEmpty()) {
            polygon.setAlertRules(List.of("ENTRY_EXIT_TRACKING"));
        } else {
            List<String> cleanRules = new ArrayList<>();
            for (String rawRule : polygon.getAlertRules()) {
                if (rawRule == null || rawRule.isBlank()) continue;
                String rule = rawRule.trim().toUpperCase();
                if (!ALLOWED_ALERT_RULES.contains(rule)) {
                    throw new CameraException(CameraErrorCode.ERR_ROI_007);
                }
                if (!cleanRules.contains(rule)) {
                    cleanRules.add(rule);
                }
            }
            if (cleanRules.isEmpty()) {
                cleanRules.add("ENTRY_EXIT_TRACKING");
            }
            polygon.setAlertRules(cleanRules);
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
