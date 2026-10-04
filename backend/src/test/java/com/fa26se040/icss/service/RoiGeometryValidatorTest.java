package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RoiGeometryValidatorTest {

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
    @DisplayName("Validate: should pass with valid polygon")
    void testValidatePassWithValidPolygon() {
        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Khu vực A")
                .vertices(validVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        assertDoesNotThrow(() -> validator.validate(geometry));
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_001 if polygons is empty")
    void testValidateEmptyGeometryThrows() {
        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of())
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_001, ex.getErrorCode());
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_002 if polygon has less than 3 vertices")
    void testValidatePolygonLessThan3Vertices() {
        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Đoạn thẳng")
                .vertices(List.of(
                        RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.1")).y(new BigDecimal("0.1")).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5")).y(new BigDecimal("0.1")).build()
                ))
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_002, ex.getErrorCode());
    }

    @Test
    @DisplayName("Validate: should throw ERR_ROI_004 if polygon has duplicate vertices resulting in < 3 distinct")
    void testValidatePolygonDuplicateVertices() {
        RoiGeometry.RoiPolygon polygon = RoiGeometry.RoiPolygon.builder()
                .label("Trùng đỉnh")
                .vertices(List.of(
                        RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.1")).y(new BigDecimal("0.1")).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.1")).y(new BigDecimal("0.1")).build(),
                        RoiGeometry.RoiPolygon.Vertex.builder().x(new BigDecimal("0.5")).y(new BigDecimal("0.5")).build()
                ))
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_004, ex.getErrorCode());
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
                .vertices(invalidVertices)
                .build();

        RoiGeometry geometry = RoiGeometry.builder()
                .polygons(List.of(polygon))
                .build();

        CameraException ex = assertThrows(CameraException.class, () -> validator.validate(geometry));
        assertEquals(CameraErrorCode.ERR_ROI_003, ex.getErrorCode());
    }
}
