package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.dto.area.EventScheduleCancelRequest;
import com.fa26se040.icss.dto.area.EventScheduleRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Step 5b — version của khu vực (BR-TC-13, TC-13, TC-13b, TC-13c, TC-17, TC-18). Test viết trước, phải ĐỎ.
 * Hợp đồng: thiếu version -> 400 ERR_AREA_044; version cũ -> 409 ERR_AREA_045; đúng -> 2xx, version tăng 1.
 */
public class Step5bVersionTest extends Step5bTestSupport {

    private static final String ERR_MISSING = "ERR_AREA_044";
    private static final String ERR_STALE = "ERR_AREA_045";

    private void assertMissingVersion(MvcResult r) throws Exception {
        assertEquals(400, status(r), describe(r));
        assertEquals(ERR_MISSING, errorCode(r), describe(r));
    }

    private void assertStaleVersion(MvcResult r) throws Exception {
        assertEquals(409, status(r), describe(r));
        assertEquals(ERR_STALE, errorCode(r), describe(r));
    }

    private void assertIncrementedBy1(Long v0, Area area) throws Exception {
        assertNotNull(v0, "AreaResponse phải trả version (BR-TC-13)");
        assertEquals(v0 + 1, apiVersion(area), "version phải tăng đúng 1");
        assertEquals(v0 + 1, dbVersion(area), "areas.version trong DB phải tăng đúng 1");
    }

    // ================================================================== TC-13: PUT /api/areas/{id}

    @Test
    @DisplayName("TC-13 PUT area (BR-TC-13): thiếu version -> 400 ERR_AREA_044, không đổi gì, không audit")
    void tc13_BR_TC_13_putArea_missingVersion_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = putArea(admin, area, area.getName() + " A", AreaLevel.INTERNAL_CONFIDENTIAL, null, null);

        assertMissingVersion(r);
        assertEquals(area.getName(), reload(area).getName());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("TC-13 PUT area (BR-TC-13): version cũ -> 409 ERR_AREA_045, dữ liệu người trước giữ nguyên")
    void tc13_BR_TC_13_putArea_staleVersion_conflict() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        String first = area.getName() + " A";

        MvcResult ok = putArea(admin, area, first, AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);
        assertEquals(200, status(ok), describe(ok));
        MvcResult stale = putArea(admin, area, area.getName() + " B", AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);

        assertStaleVersion(stale);
        assertEquals(first, reload(area).getName(), "Không được ghi đè dữ liệu của người trước");
        assertEquals(1, auditsWithAction(area, "UPDATE").size(), "Chỉ lần ghi thành công có audit");
    }

    @Test
    @DisplayName("TC-13 PUT area (BR-TC-13): version đúng -> 200, version tăng 1")
    void tc13_BR_TC_13_putArea_correctVersion_incrementsVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = putArea(admin, area, area.getName() + " A", AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);

        assertEquals(200, status(r), describe(r));
        assertIncrementedBy1(v0, area);
        assertEquals(v0 + 1, responseVersion(r), "Response PUT phải trả version mới");
    }

    // ================================================================== TC-13: PATCH access-rules

    @Test
    @DisplayName("TC-13 PATCH access-rules (BR-TC-13): thiếu version -> 400 ERR_AREA_044, không đổi gì, không audit")
    void tc13_BR_TC_13_accessRules_missingVersion_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchAccessRules(fm, area, 2, true, null);

        assertMissingVersion(r);
        assertFalse(reload(area).getExplicitAuthorizationRequired());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("TC-13 PATCH access-rules (BR-TC-13): version cũ -> 409 ERR_AREA_045, dữ liệu người trước giữ nguyên")
    void tc13_BR_TC_13_accessRules_staleVersion_conflict() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult ok = patchAccessRules(fm, area, 2, true, v0);
        assertEquals(200, status(ok), describe(ok));
        MvcResult stale = patchAccessRules(fm2, area, 3, true, v0);

        assertStaleVersion(stale);
        Area after = reload(area);
        assertEquals(2, after.getAreaAccessLevel(), "Không được ghi đè cấp của người trước");
        assertTrue(after.getExplicitAuthorizationRequired());
        assertEquals(1, auditsWithAction(area, "UPDATE").size());
    }

    @Test
    @DisplayName("TC-13 PATCH access-rules (BR-TC-13): version đúng -> 200, version tăng 1")
    void tc13_BR_TC_13_accessRules_correctVersion_incrementsVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = patchAccessRules(fm, area, 2, true, v0);

        assertEquals(200, status(r), describe(r));
        assertIncrementedBy1(v0, area);
    }

    // ================================================================== TC-13: PATCH event-mode

    @Test
    @DisplayName("TC-13 PATCH event-mode (BR-TC-13): thiếu version -> 400 ERR_AREA_044, không mở sự kiện, không audit, không thông báo")
    void tc13_BR_TC_13_eventMode_missingVersion_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, null));

        assertMissingVersion(r);
        assertFalse(reload(area).getOpenToMembers());
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).isEmpty());
        assertEquals(auditBefore, auditCountForArea(area));
        assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }

    @Test
    @DisplayName("TC-13 PATCH event-mode (BR-TC-13): version cũ -> 409 ERR_AREA_045, sự kiện của người trước giữ nguyên")
    void tc13_BR_TC_13_eventMode_staleVersion_conflict() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        OffsetDateTime until = OffsetDateTime.now().plusHours(2).withNano(0);

        MvcResult ok = patchEventMode(fm, area, eventBody("ENABLE", until, "SEMINAR", EVENT_NOTE, v0));
        assertEquals(200, status(ok), describe(ok));
        MvcResult stale = patchEventMode(fm2, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, v0));

        assertStaleVersion(stale);
        Area after = reload(area);
        assertTrue(after.isEventActive(OffsetDateTime.now()), "Sự kiện của người trước phải giữ nguyên");
        assertEquals(until.toInstant(), after.getOpenUntil().toInstant());
        assertEquals(0, auditsWithAction(area, "DISABLE_EVENT_MODE").size());
    }

    @Test
    @DisplayName("TC-13 PATCH event-mode (BR-TC-13): version đúng -> 200, version tăng 1")
    void tc13_BR_TC_13_eventMode_correctVersion_incrementsVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, v0));

        assertEquals(200, status(r), describe(r));
        assertIncrementedBy1(v0, area);
    }

    // ================================================================== TC-13: lưu hình học

    @Test
    @DisplayName("TC-13 lưu hình học (BR-TC-13): thiếu version -> 400 ERR_AREA_044, không lưu, không audit")
    void tc13_BR_TC_13_saveGeometry_missingVersion_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = saveGeometry(admin, area, square(0.1, 0.1, 0.2), null);

        assertMissingVersion(r);
        assertNull(reload(area).getGeometry());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("TC-13 lưu hình học (BR-TC-13): version cũ -> 409 ERR_AREA_045, hình học của người trước giữ nguyên")
    void tc13_BR_TC_13_saveGeometry_staleVersion_conflict() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult ok = saveGeometry(admin, area, square(0.1, 0.1, 0.2), v0);
        assertEquals(200, status(ok), describe(ok));
        MvcResult stale = saveGeometry(admin, area, square(0.5, 0.5, 0.2), v0);

        assertStaleVersion(stale);
        AreaGeometry g = reload(area).getGeometry();
        assertNotNull(g);
        assertEquals(0, g.getVertices().get(0).getX().compareTo(new java.math.BigDecimal("0.1")), "Hình học người trước giữ nguyên");
        assertEquals(1, auditsWithAction(area, "UPDATE_GEOMETRY").size());
    }

    @Test
    @DisplayName("TC-13 lưu hình học (BR-TC-13): version đúng -> 200, version tăng 1")
    void tc13_BR_TC_13_saveGeometry_correctVersion_incrementsVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = saveGeometry(admin, area, square(0.1, 0.1, 0.2), v0);

        assertEquals(200, status(r), describe(r));
        assertIncrementedBy1(v0, area);
    }

    // ================================================================== TC-13: xoá hình học

    private Area areaWithGeometry() {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        area.setGeometry(square(0.1, 0.1, 0.2));
        return areaRepository.save(area);
    }

    @Test
    @DisplayName("TC-13 xoá hình học (BR-TC-13): thiếu version -> 400 ERR_AREA_044, hình học còn nguyên, không audit")
    void tc13_BR_TC_13_deleteGeometry_missingVersion_badRequest() throws Exception {
        Area area = areaWithGeometry();
        long auditBefore = auditCountForArea(area);

        MvcResult r = deleteGeometry(admin, area, null);

        assertMissingVersion(r);
        assertNotNull(reload(area).getGeometry());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("TC-13 xoá hình học (BR-TC-13): version cũ -> 409 ERR_AREA_045, hình học còn nguyên")
    void tc13_BR_TC_13_deleteGeometry_staleVersion_conflict() throws Exception {
        Area area = areaWithGeometry();
        Long v0 = apiVersion(area);

        MvcResult ok = saveGeometry(admin, area, square(0.5, 0.5, 0.2), v0);
        assertEquals(200, status(ok), describe(ok));
        MvcResult stale = deleteGeometry(admin, area, v0);

        assertStaleVersion(stale);
        assertNotNull(reload(area).getGeometry(), "Hình học không được bị xoá bằng version cũ");
        assertEquals(0, auditsWithAction(area, "DELETE_GEOMETRY").size());
    }

    @Test
    @DisplayName("TC-13 xoá hình học (BR-TC-13): version đúng -> 200, version tăng 1")
    void tc13_BR_TC_13_deleteGeometry_correctVersion_incrementsVersion() throws Exception {
        Area area = areaWithGeometry();
        Long v0 = apiVersion(area);

        MvcResult r = deleteGeometry(admin, area, v0);

        assertEquals(200, status(r), describe(r));
        assertNull(reload(area).getGeometry());
        assertIncrementedBy1(v0, area);
    }

    // ================================================================== TC-13b

    @Test
    @DisplayName("TC-13b (BR-TC-13): AreaResponse và AreaListItemResponse có version")
    void tc13b_BR_TC_13_responsesExposeVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);

        MvcResult detail = send(get("/api/areas/{id}", area.getId()), admin, null).andReturn();
        assertEquals(200, status(detail), describe(detail));
        JsonNode dv = json(detail).path("data").path("version");
        assertTrue(dv.isIntegralNumber(), "AreaResponse.version phải là số: " + dv);

        MvcResult list = send(get("/api/areas").param("keyword", area.getName()).param("building", building.getCode()),
                admin, null).andReturn();
        assertEquals(200, status(list), describe(list));
        JsonNode item = null;
        for (JsonNode n : json(list).path("data").path("content")) {
            if (area.getId().toString().equals(n.path("id").asText())) {
                item = n;
            }
        }
        assertNotNull(item, "Danh sách phải có khu vực vừa tạo");
        assertTrue(item.path("version").isIntegralNumber(), "AreaListItemResponse.version phải là số: " + item.path("version"));
        assertEquals(dv.asLong(), item.path("version").asLong());
        assertEquals(dv.asLong(), dbVersion(area));
    }

    // ================================================================== TC-13c

    @Test
    @DisplayName("TC-13c (BR-TC-13): 2 luồng PUT song song cùng version, 10 vòng -> đúng 1 thành công, 1 nhận 409 ERR_AREA_045, không ghi đè")
    void tc13c_BR_TC_13_concurrentSameVersion_exactlyOneWins() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Long version = areaService.getAreaById(area.getId()).version();
                String nameA = area.getName() + " A" + round;
                String nameB = area.getName() + " B" + round;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger success = new AtomicInteger();
                AtomicInteger stale = new AtomicInteger();
                AtomicReference<String> winner = new AtomicReference<>();
                List<Throwable> unexpected = new CopyOnWriteArrayList<>();

                for (String name : List.of(nameA, nameB)) {
                    executor.submit(() -> {
                        try {
                            start.await();
                            areaService.update(area.getId(), AreaUpdateRequest.builder()
                                    .name(name)
                                    .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                                    .building(area.getBuilding())
                                    .floor(area.getFloor())
                                    .floorId(floor.getId())
                                    .centerLatitude(CENTER_LAT)
                                    .centerLongitude(CENTER_LNG)
                                    .version(version)
                                    .build(), admin.getEmail());
                            success.incrementAndGet();
                            winner.set(name);
                        } catch (AreaException ex) {
                            if (ex.getErrorCode() == AreaErrorCode.ERR_AREA_045) {
                                stale.incrementAndGet();
                            } else {
                                unexpected.add(ex);
                            }
                        } catch (Throwable t) {
                            unexpected.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(20, TimeUnit.SECONDS), "Vòng " + round + ": quá thời gian");

                assertEquals(1, success.get(), "Vòng " + round + ": đúng 1 luồng thành công (unexpected=" + unexpected + ")");
                assertEquals(1, stale.get(), "Vòng " + round + ": đúng 1 luồng nhận ERR_AREA_045 (unexpected=" + unexpected + ")");
                assertEquals(winner.get(), reload(area).getName(), "Vòng " + round + ": dữ liệu người thắng không bị ghi đè");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ================================================================== TC-17

    @Test
    @DisplayName("TC-17 (BR-TC-13): job kích hoạt lịch ghi areas -> version tăng; client giữ version cũ -> 409 ERR_AREA_045")
    void tc17_BR_TC_13_scheduleActivationJob_incrementsVersion() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule schedule = newSchedule(area, now.minusMinutes(1), now.plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        Long v0 = apiVersion(area);

        areaService.activateScheduleInTx(schedule.getId(), now);

        assertEquals(AreaEventScheduleStatus.STARTED, eventScheduleRepository.findById(schedule.getId()).orElseThrow().getStatus(),
                "Tiền đề: job phải kích hoạt lịch");
        assertTrue(reload(area).getOpenToMembers());
        assertIncrementedBy1(v0, area);
        MvcResult stale = patchAccessRules(fm, area, 3, true, v0);
        assertStaleVersion(stale);
        assertEquals(2, reload(area).getAreaAccessLevel());
    }

    @Test
    @DisplayName("TC-17 (BR-TC-13): job đóng phiên hết hạn ghi areas -> version tăng; client giữ version cũ -> 409 ERR_AREA_045")
    void tc17_BR_TC_13_expiryJob_incrementsVersion() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusHours(2), now.minusMinutes(1));
        Long v0 = apiVersion(area);

        areaService.expireEventSessionForAreaInTx(area.getId(), OffsetDateTime.now());

        assertFalse(reload(area).getOpenToMembers(), "Tiền đề: job phải tắt cờ sự kiện");
        assertIncrementedBy1(v0, area);
        MvcResult stale = patchAccessRules(fm, area, 3, true, v0);
        assertStaleVersion(stale);
        assertEquals(2, reload(area).getAreaAccessLevel());
    }

    @Test
    @DisplayName("TC-17 (BR-TC-13): dọn phiên hết hạn khi FM đặt lịch (cleanupExpiredSessions) ghi areas -> version tăng; version cũ -> 409")
    void tc17_BR_TC_13_lazyCleanupOnScheduleCreate_incrementsVersion() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusHours(2), now.minusMinutes(1));
        Long v0 = apiVersion(area);

        OffsetDateTime start = now.plusDays(1).withNano(0);
        MvcResult created = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                new EventScheduleRequest(start, start.plusHours(2), "OTHER", EVENT_NOTE)).andReturn();
        assertEquals(201, status(created), describe(created));

        assertFalse(reload(area).getOpenToMembers(), "Tiền đề: dọn phiên hết hạn phải tắt cờ sự kiện");
        assertIncrementedBy1(v0, area);
        assertStaleVersion(patchAccessRules(fm, area, 3, true, v0));
    }

    @Test
    @DisplayName("TC-17 (BR-TC-13): vô hiệu hoá khu vực ghi areas -> version tăng")
    void tc17_BR_TC_13_deactivate_incrementsVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = send(delete("/api/areas/{id}", area.getId()), admin, null).andReturn();

        assertEquals(200, status(r), describe(r));
        assertNotNull(reload(area).getDeletedAt(), "Tiền đề: khu vực phải bị vô hiệu hoá");
        assertIncrementedBy1(v0, area);
    }

    @Test
    @DisplayName("TC-17b (BR-TC-13): PUT dữ liệu y hệt -> 200, version không đổi")
    void tc17b_BR_TC_13_putIdenticalData_versionUnchanged() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = putArea(admin, area, area.getName(), AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);

        assertEquals(200, status(r), describe(r));
        assertNotNull(v0, "AreaResponse phải trả version (BR-TC-13)");
        assertEquals(v0, apiVersion(area), "Lưu không đổi dữ liệu không được tăng version");
        assertEquals(v0, dbVersion(area));
    }

    @Test
    @DisplayName("TC-17c (BR-TC-13, audit toạ độ): chỉ đổi toạ độ -> version +1, audit UPDATE có toạ độ trước/sau")
    void tc17c_BR_TC_13_coordinatesOnly_incrementsVersion_auditHasCoordinates() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);

        MvcResult r = send(put("/api/areas/{id}", area.getId()), admin,
                putBody(area, area.getName(), AreaLevel.INTERNAL_CONFIDENTIAL, null, v0, 10.8425, 106.8111)).andReturn();

        assertEquals(200, status(r), describe(r));
        Area after = reload(area);
        assertEquals(10.8425, after.getCenterLatitude());
        assertEquals(106.8111, after.getCenterLongitude());
        assertIncrementedBy1(v0, area);
        List<AuditLog> updates = auditsWithAction(area, "UPDATE");
        assertEquals(1, updates.size());
        JsonNode oldV = objectMapper.readTree(updates.get(0).getOldValue());
        JsonNode newV = objectMapper.readTree(updates.get(0).getNewValue());
        assertEquals(CENTER_LAT, oldV.path("centerLatitude").asDouble(), 1e-9, "Snapshot trước phải có vĩ độ cũ: " + oldV);
        assertEquals(CENTER_LNG, oldV.path("centerLongitude").asDouble(), 1e-9, "Snapshot trước phải có kinh độ cũ: " + oldV);
        assertEquals(10.8425, newV.path("centerLatitude").asDouble(), 1e-9, "Snapshot sau phải có vĩ độ mới: " + newV);
        assertEquals(106.8111, newV.path("centerLongitude").asDouble(), 1e-9, "Snapshot sau phải có kinh độ mới: " + newV);
    }

    // ================================================================== TC-18

    @Test
    @DisplayName("TC-18 (BR-TC-13): tạo / sửa / huỷ lịch không cần version -> 2xx")
    void tc18_BR_TC_13_scheduleEndpoints_doNotRequireVersion() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);

        MvcResult created = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                new EventScheduleRequest(start, start.plusHours(2), "OTHER", EVENT_NOTE)).andReturn();
        assertEquals(201, status(created), describe(created));
        UUID scheduleId = UUID.fromString(json(created).path("data").path("id").asText());

        MvcResult updated = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), scheduleId), fm,
                new EventScheduleRequest(start.plusHours(1), start.plusHours(3), "OTHER", EVENT_NOTE)).andReturn();
        assertEquals(200, status(updated), describe(updated));

        MvcResult cancelled = send(post("/api/areas/{id}/event-schedules/{sid}/cancel", area.getId(), scheduleId), fm,
                new EventScheduleCancelRequest("OTHER", "Huỷ lịch dữ liệu test 5b")).andReturn();
        assertEquals(200, status(cancelled), describe(cancelled));

        assertEquals(AreaEventScheduleStatus.CANCELLED, eventScheduleRepository.findById(scheduleId).orElseThrow().getStatus());
        assertEquals(3, auditsForTarget(scheduleId.toString()).size(), "Tạo + sửa + huỷ -> 3 audit");
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).isEmpty(), "GUARD nhận thông báo lịch");
    }
}
