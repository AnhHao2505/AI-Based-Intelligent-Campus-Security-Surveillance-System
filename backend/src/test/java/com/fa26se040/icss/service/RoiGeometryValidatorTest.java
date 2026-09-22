package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.AreaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoiGeometryValidatorTest {

    @Mock
    private AreaRepository areaRepository;

    @InjectMocks
    private RoiGeometryValidator validator;

    private List<RoiGeometry.RoiPolygon.Vertex> validVertices;

    @BeforeEach
    void setUp() {
        validVertices = List.of(
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.1000")).y(new BigDecimal("0.1000")).build(),
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5000")).y(new BigDecimal("0.1000")).build(),
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5000")).y(new BigDecimal("0.5000")).build()
        );
    }

    @Test
    @DisplayName("Validate: should pass with all 3 allowed alert rules")
    void testValidatePassWithAllowedAlertRules() {
        List<String> rules = List.of(
                "ENTRY_EXIT_TRACKING",
                "LOITERING",
                "CROWD_OVERCROWDING"
        );

        for (String rule : rules) {
            RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                    .label("Test Polygon " + rule)
                    .alertRules(new ArrayList<>(List.of(rule)))
                    .vertices(validVertices)
                    .build();

            RoiGeometry geometry = RoiGeometry.builder()
                    .polygons(List.of(polygon))
                    .build();

            assertDoesNotThrow(() -> validator.validate(geometry));
            assertTrue(polygon.getAlertRules().contains(rule));
        }
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_007 when using removed or invalid alert rules")
    void testValidateRejectsNonAllowedRules() {
        List<String> invalidRules = List.of(
                "UNAUTHORIZED_ENTRY",
                "UNKNOWN_PERSON",
                "AFTER_HOURS_ACCESS",
                "INTRUSION_DETECTION",
                "UNAUTHORIZED_ACCESS"
        );

        for (String invalidRule : invalidRules) {
            RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                    .label("Invalid Rule Polygon")
                    .alertRules(List.of(invalidRule))
                    .vertices(validVertices)
                    .build();

            RoiGeometry geometry = RoiGeometry.builder()
                    .polygons(List.of(polygon))
                    .build();

            CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
            assertEquals(CameraErrorCode.ERR_ROI_007, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("Validate: should default to ENTRY_EXIT_TRACKING if alertRules is null or empty")
    void testValidateDefaultAlertRuleWhenEmpty() {
        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Empty Rules Polygon")
                .alertRules(null)
                .vertices(validVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        assertDoesNotThrow(() -> validator.validate(geometry));
        assertEquals(List.of("ENTRY_EXIT_TRACKING"), polygon.getAlertRules());
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_007 when alert rule is completely invalid")
    void testValidateThrowsOnInvalidRule() {
        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Invalid Rule Polygon")
                .alertRules(List.of("COMPLETELY_UNKNOWN_RULE_XYZ"))
                .vertices(validVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_007, ex.getErrorCode());
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_006 when target area does not exist")
    void testValidateThrowsWhenTargetAreaNotFound() {
        UUID areaId = UUID.randomUUID();
        when(areaRepository.existsByIdAndDeletedAtIsNull(areaId)).thenReturn(false);

        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Area Polygon")
                .targetAreaId(areaId)
                .alertRules(List.of("LOITERING"))
                .vertices(validVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_006, ex.getErrorCode());
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_003 when coordinate is outside [0.0, 1.0]")
    void testValidateThrowsWhenCoordinateOutOfBounds() {
        List<RoiGeometry.RoiPolygon.Vertex> invalidVertices = List.of(
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("1.5000")).y(new BigDecimal("0.1000")).build(),
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5000")).y(new BigDecimal("0.1000")).build(),
                RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5000")).y(new BigDecimal("0.5000")).build()
        );

        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Out of bounds Polygon")
                .alertRules(List.of("LOITERING"))
                .vertices(invalidVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_003, ex.getErrorCode());
    }
}
