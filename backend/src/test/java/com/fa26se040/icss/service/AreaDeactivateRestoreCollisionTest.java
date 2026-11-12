package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.guest.GuestTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Mục 2A: Vô hiệu hoá giải phóng toạ độ block 3D (null/null, giữ building/floor & audit) +
 * Khôi phục tự động tách toạ độ nếu block cũ trên cùng tầng đang bị khu vực active khác chiếm (<= 3m).
 */
class AreaDeactivateRestoreCollisionTest extends GuestTestSupport {

    private static final String DEACTIVATE_REASON = "Tạm đóng phòng để bảo trì hệ thống điện nước";
    private static final String RESTORE_REASON = "Đã hoàn tất bảo trì, mở lại sử dụng phòng";

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> actionBody(String reason, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("version", version);
        return body;
    }

    private Long dbVersion(Area area) {
        return jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());
    }

    @Test
    @DisplayName("2A Happy Path: deactivate xoá centerLatitude/Longitude về null, giữ building/floor và audit snapshot")
    void deactivate_clearsCoordinatesWhilePreservingBuildingFloorAndAudit() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        Double originalLat = area.getCenterLatitude();
        Double originalLng = area.getCenterLongitude();
        assertNotNull(originalLat);
        assertNotNull(originalLng);

        MvcResult r = send(post("/api/areas/{id}/deactivate", area.getId()),
                admin, actionBody(DEACTIVATE_REASON, dbVersion(area))).andReturn();

        assertEquals(200, status(r), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Area after = reload(area);
        assertFalse(after.getIsActive());
        assertNotNull(after.getDeletedAt());
        assertNull(after.getCenterLatitude(), "Deactivate phải giải phóng tọa độ block 3D");
        assertNull(after.getCenterLongitude(), "Deactivate phải giải phóng tọa độ block 3D");
        assertEquals(building.getName(), after.getBuilding());
        assertEquals(floor.getName(), after.getFloor());
        assertEquals(floor.getId(), after.getFloorEntity().getId());

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT old_value::text AS old_json, new_value::text AS new_json FROM audit_logs "
                        + "WHERE target_type = 'AREA' AND action = 'DEACTIVATE' AND target_id = ?",
                area.getId().toString());
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).get("old_json").toString().contains(String.valueOf(originalLat)));
    }

    @Test
    @DisplayName("2A Collision Boundary (<= 3m cùng tầng): restore khu vực còn tọa độ cũ bị phòng khác chiếm -> tự tách null/null")
    void restore_occupiedWithinThreeMetersOnSameFloor_autoDetachesCoordinates() throws Exception {
        double lat = 10.841000;
        double lng = 106.810000;

        Area occupant = areaRepository.save(Area.builder()
                .name("Phong dang chiem block " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .centerLatitude(lat)
                .centerLongitude(lng)
                .isActive(true)
                .build());

        // Legacy deactivated row that still holds colliding coordinates (~2.0m away on same floor)
        Area deactivatedLegacy = areaRepository.save(Area.builder()
                .name("Phong cu restore " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .centerLatitude(lat + 0.000018)
                .centerLongitude(lng + 0.000010)
                .isActive(false)
                .deletedAt(OffsetDateTime.now().minusDays(1))
                .build());

        assertNotNull(occupant.getId());
        MvcResult r = send(post("/api/areas/{id}/restore", deactivatedLegacy.getId()),
                admin, actionBody(RESTORE_REASON, dbVersion(deactivatedLegacy))).andReturn();

        assertEquals(200, status(r), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Area restored = reload(deactivatedLegacy);
        assertTrue(restored.getIsActive());
        assertNull(restored.getDeletedAt());
        assertNull(restored.getCenterLatitude(), "Block đã có phòng khác chiếm -> tự động gỡ tọa độ về null");
        assertNull(restored.getCenterLongitude(), "Block đã có phòng khác chiếm -> tự động gỡ tọa độ về null");
        assertTrue(json(r).path("data").path("centerLatitude").isNull());
        assertTrue(json(r).path("data").path("centerLongitude").isNull());
    }

    @Test
    @DisplayName("2A Isolation Boundary (> 3m cùng tầng hoặc cùng XY khác tầng): restore giữ nguyên tọa độ")
    void restore_beyondThreeMetersOrDifferentFloor_keepsCoordinates() throws Exception {
        double lat = 10.841200;
        double lng = 106.810200;

        Floor upperFloor = floorRepository.save(Floor.builder()
                .name("T2 " + suffix)
                .floorOrder(2)
                .building(building)
                .isActive(true)
                .build());

        // Active occupant on upperFloor at exact same XY -> different floor, must NOT collide
        areaRepository.save(Area.builder()
                .name("Phong tang tren " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .building(building.getName())
                .floor(upperFloor.getName())
                .floorEntity(upperFloor)
                .centerLatitude(lat)
                .centerLongitude(lng)
                .isActive(true)
                .build());

        // Active occupant on same floor ~6.7m away (> 3.0m) -> must NOT collide
        areaRepository.save(Area.builder()
                .name("Phong ben canh " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .centerLatitude(lat + 0.00006)
                .centerLongitude(lng)
                .isActive(true)
                .build());

        Area legacyTarget = areaRepository.save(Area.builder()
                .name("Phong khoi phuc khong trung " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .centerLatitude(lat)
                .centerLongitude(lng)
                .isActive(false)
                .deletedAt(OffsetDateTime.now().minusHours(6))
                .build());

        MvcResult r = send(post("/api/areas/{id}/restore", legacyTarget.getId()),
                admin, actionBody(RESTORE_REASON, dbVersion(legacyTarget))).andReturn();

        assertEquals(200, status(r), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Area restored = reload(legacyTarget);
        assertEquals(lat, restored.getCenterLatitude(), 1e-9);
        assertEquals(lng, restored.getCenterLongitude(), 1e-9);
    }
}
