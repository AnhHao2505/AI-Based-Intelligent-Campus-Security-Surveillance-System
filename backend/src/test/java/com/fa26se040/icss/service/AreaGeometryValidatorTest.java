package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaGeometryValidatorTest {

    private AreaGeometryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AreaGeometryValidator();
    }

    private AreaGeometry.Vertex v(String x, String y) {
        return AreaGeometry.Vertex.builder()
                .x(new BigDecimal(x))
                .y(new BigDecimal(y))
                .build();
    }

    private AreaGeometry geom(AreaGeometry.Vertex... vertices) {
        return AreaGeometry.builder()
                .type("polygon")
                .version(1)
                .vertices(List.of(vertices))
                .build();
    }

    @Test
    @DisplayName("Building is null -> rejected (ERR_AREA_015)")
    void testNullBuildingRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.3", "0.5"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), null, "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_015, ex.getErrorCode());
    }

    @Test
    @DisplayName("Building is blank -> rejected (ERR_AREA_015)")
    void testBlankBuildingRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.3", "0.5"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "   ", "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_015, ex.getErrorCode());
    }

    @Test
    @DisplayName("Floor is null -> rejected (ERR_AREA_015)")
    void testNullFloorRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.3", "0.5"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "FPT_AROUND", null, Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_015, ex.getErrorCode());
    }

    @Test
    @DisplayName("Floor is blank -> rejected (ERR_AREA_015)")
    void testBlankFloorRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.3", "0.5"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "FPT_AROUND", "", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_015, ex.getErrorCode());
    }

    @Test
    @DisplayName("2 vertices -> rejected (ERR_AREA_011)")
    void testTwoVerticesRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.5"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_011, ex.getErrorCode());
    }

    @Test
    @DisplayName("3 vertices -> accepted")
    void testThreeVerticesAccepted() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.3", "0.5"));

        assertDoesNotThrow(() -> validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
    }

    @Test
    @DisplayName("Coordinate at 1.5 -> rejected (ERR_AREA_012)")
    void testCoordinateGreaterThanOneRejected() {
        AreaGeometry g = geom(v("0.1", "0.1"), v("1.5", "0.5"), v("0.5", "0.8"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_012, ex.getErrorCode());
    }

    @Test
    @DisplayName("Coordinate at -0.1 -> rejected (ERR_AREA_012)")
    void testCoordinateLessThanZeroRejected() {
        AreaGeometry g = geom(v("-0.1", "0.1"), v("0.5", "0.5"), v("0.5", "0.8"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_012, ex.getErrorCode());
    }

    @Test
    @DisplayName("Coordinates exactly 0 and 1 -> accepted")
    void testBoundaryCoordinatesZeroAndOneAccepted() {
        AreaGeometry g = geom(v("0.0", "0.0"), v("1.0", "0.0"), v("1.0", "1.0"), v("0.0", "1.0"));

        assertDoesNotThrow(() -> validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
    }

    @Test
    @DisplayName("Two clearly separated shapes -> accepted")
    void testTwoSeparatedShapesAccepted() {
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        AreaGeometry currentGeom = geom(v("0.1", "0.1"), v("0.3", "0.1"), v("0.3", "0.3"), v("0.1", "0.3"));
        AreaGeometry otherGeom = geom(v("0.5", "0.5"), v("0.8", "0.5"), v("0.8", "0.8"), v("0.5", "0.8"));

        Area otherArea = Area.builder()
                .id(otherId)
                .geometry(otherGeom)
                .build();

        assertDoesNotThrow(() -> validator.validate(currentGeom, currentId, "A", "1", List.of(otherArea)));
    }

    @Test
    @DisplayName("Two clearly overlapping shapes -> rejected (ERR_AREA_013)")
    void testTwoOverlappingShapesRejected() {
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        AreaGeometry currentGeom = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.5", "0.5"), v("0.1", "0.5"));
        AreaGeometry otherGeom = geom(v("0.3", "0.3"), v("0.7", "0.3"), v("0.7", "0.7"), v("0.3", "0.7"));

        Area otherArea = Area.builder()
                .id(otherId)
                .geometry(otherGeom)
                .build();

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(currentGeom, currentId, "A", "1", List.of(otherArea)));
        assertEquals(AreaErrorCode.ERR_AREA_013, ex.getErrorCode());
    }

    @Test
    @DisplayName("Two shapes sharing exactly one edge -> accepted (BR-04)")
    void testTwoShapesSharingOneEdgeAccepted() {
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        // Shape A: x from 0.1 to 0.4, y from 0.1 to 0.4
        AreaGeometry currentGeom = geom(v("0.1", "0.1"), v("0.4", "0.1"), v("0.4", "0.4"), v("0.1", "0.4"));
        // Shape B: x from 0.4 to 0.8, y from 0.1 to 0.4 (Shares edge x = 0.4)
        AreaGeometry otherGeom = geom(v("0.4", "0.1"), v("0.8", "0.1"), v("0.8", "0.4"), v("0.4", "0.4"));

        Area otherArea = Area.builder()
                .id(otherId)
                .geometry(otherGeom)
                .build();

        assertDoesNotThrow(() -> validator.validate(currentGeom, currentId, "A", "1", List.of(otherArea)));
    }

    @Test
    @DisplayName("Updating a shape against itself -> accepted (self excluded)")
    void testUpdatingShapeAgainstSelfAccepted() {
        UUID sameId = UUID.randomUUID();

        AreaGeometry currentGeom = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.5", "0.5"), v("0.1", "0.5"));
        AreaGeometry existingGeom = geom(v("0.1", "0.1"), v("0.5", "0.1"), v("0.5", "0.5"), v("0.1", "0.5"));

        Area selfArea = Area.builder()
                .id(sameId)
                .geometry(existingGeom)
                .build();

        assertDoesNotThrow(() -> validator.validate(currentGeom, sameId, "A", "1", List.of(selfArea)));
    }

    @Test
    @DisplayName("FIX-4: 3 identical vertices (even with trailing zeros) -> rejected (ERR_AREA_016)")
    void testThreeIdenticalVerticesRejected() {
        // Points with different scale like 0.1 and 0.10 should be considered identical via BigDecimal.compareTo
        AreaGeometry g = geom(v("0.1", "0.1"), v("0.10", "0.10"), v("0.100", "0.100"));

        AreaException ex = assertThrows(AreaException.class, () ->
                validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
        assertEquals(AreaErrorCode.ERR_AREA_016, ex.getErrorCode());
    }

    @Test
    @DisplayName("FIX-4: 4 vertices where 2 are identical (3 distinct) -> accepted")
    void testFourVerticesWithDuplicateAccepted() {
        AreaGeometry g = geom(
                v("0.1", "0.1"),
                v("0.10", "0.10"), // Duplicate of vertex 1
                v("0.5", "0.1"),
                v("0.3", "0.5")
        );

        assertDoesNotThrow(() -> validator.validate(g, UUID.randomUUID(), "A", "1", Collections.emptyList()));
    }
}
