package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Step 5b — trạng thái lịch theo phiên nó sinh ra (BR-ES-S1..S3). Test viết trước, phải ĐỎ.
 * STARTED -> COMPLETED (phiên đóng do hết giờ) | ENDED_EARLY (FM tắt phiên đó); ADJUST không sửa bản ghi lịch.
 * Trạng thái so bằng name() để test biên dịch được trước khi enum có COMPLETED / ENDED_EARLY.
 */
public class Step5bScheduleStatusTest extends Step5bTestSupport {

    private AreaEventSchedule startedSchedule(Area area, OffsetDateTime start, OffsetDateTime end, OffsetDateTime activateAt) {
        AreaEventSchedule s = newSchedule(area, start, end, "PLANNED_EVENT", "Sự kiện theo kế hoạch");
        areaService.activateScheduleInTx(s.getId(), activateAt);
        assertEquals("STARTED", scheduleStatus(s), "Tiền đề: job phải kích hoạt lịch");
        return s;
    }

    private String scheduleStatus(AreaEventSchedule s) {
        return eventScheduleRepository.findById(s.getId()).orElseThrow().getStatus().name();
    }

    private JsonNode lastAuditValue(Area area, String action) throws Exception {
        List<AuditLog> rows = auditsWithAction(area, action);
        assertEquals(1, rows.size(), "Phải có đúng 1 audit " + action);
        return objectMapper.readTree(rows.get(0).getNewValue());
    }

    // ================================================================== ES-S1

    @Test
    @DisplayName("ES-S1a (BR-ES-S1): FM tắt (DISABLE) phiên do lịch kích hoạt -> lịch ENDED_EARLY")
    void esS1a_BR_ES_S1_manualDisable_scheduleEndedEarly() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule s = startedSchedule(area, now.minusMinutes(1), now.plusHours(2), now);

        MvcResult r = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(200, status(r), describe(r));
        assertFalse(reload(area).isEventActive(OffsetDateTime.now()));
        assertEquals("ENDED_EARLY", scheduleStatus(s));
    }

    @Test
    @DisplayName("ES-S1b (BR-ES-S1): job đóng phiên hết giờ của lịch -> lịch COMPLETED")
    void esS1b_BR_ES_S1_expiryJob_scheduleCompleted() {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule s = startedSchedule(area, now.minusHours(2), now.minusMinutes(10), now.minusHours(1));

        areaService.expireEventSessionForAreaInTx(area.getId(), OffsetDateTime.now());

        assertFalse(reload(area).getOpenToMembers(), "Tiền đề: job phải đóng phiên");
        assertEquals("COMPLETED", scheduleStatus(s));
    }

    @Test
    @DisplayName("ES-S1c (BR-ES-S1): ADJUST phiên do lịch -> lịch vẫn STARTED, qua giờ kết thúc cũ vẫn STARTED; phiên kéo dài hết giờ -> COMPLETED")
    void esS1c_BR_ES_S1_adjustKeepsScheduleStarted() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule s = startedSchedule(area, now.minusMinutes(1), now.plusHours(1), now);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", now.plusHours(3), "EVENT_PROLONGED", EVENT_NOTE, apiVersion(area)));
        assertEquals(200, status(r), describe(r));
        assertEquals("STARTED", scheduleStatus(s), "ADJUST không sửa bản ghi lịch");

        areaService.expireEventSessionForAreaInTx(area.getId(), now.plusHours(1).plusMinutes(1));
        assertEquals("STARTED", scheduleStatus(s), "Qua giờ kết thúc cũ của lịch, phiên kéo dài vẫn mở -> lịch vẫn STARTED");
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).isPresent());

        areaService.expireEventSessionForAreaInTx(area.getId(), now.plusHours(3).plusMinutes(1));
        assertEquals("COMPLETED", scheduleStatus(s), "Phiên kéo dài (vẫn thuộc lịch) hết giờ -> COMPLETED");
    }

    // ================================================================== ES-S2

    @Test
    @DisplayName("ES-S2 (BR-ES-S2): audit DISABLE_EVENT_MODE ghi scheduleId + trạng thái lịch mới, không thêm dòng audit riêng cho lịch")
    void esS2_BR_ES_S2_disableAuditSnapshotHasSchedule() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule s = startedSchedule(area, now.minusMinutes(1), now.plusHours(2), now);

        MvcResult r = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));
        assertEquals(200, status(r), describe(r));

        JsonNode v = lastAuditValue(area, "DISABLE_EVENT_MODE");
        assertEquals(s.getId().toString(), v.path("scheduleId").asText(), v.toString());
        assertEquals("ENDED_EARLY", v.path("scheduleStatus").asText(), v.toString());
        assertTrue(auditsForTarget(s.getId().toString()).isEmpty(), "Không thêm dòng audit riêng cho lịch");
    }

    @Test
    @DisplayName("ES-S2 (BR-ES-S2): audit EXPIRE_EVENT_MODE ghi scheduleId + trạng thái lịch mới")
    void esS2_BR_ES_S2_expireAuditSnapshotHasSchedule() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule s = startedSchedule(area, now.minusHours(2), now.minusMinutes(10), now.minusHours(1));

        areaService.expireEventSessionForAreaInTx(area.getId(), OffsetDateTime.now());

        JsonNode v = lastAuditValue(area, "EXPIRE_EVENT_MODE");
        assertEquals(s.getId().toString(), v.path("scheduleId").asText(), v.toString());
        assertEquals("COMPLETED", v.path("scheduleStatus").asText(), v.toString());
        assertTrue(auditsForTarget(s.getId().toString()).isEmpty(), "Không thêm dòng audit riêng cho lịch");
    }

    // ================================================================== ES-S3

    private UUID insertSchedule(Area area, OffsetDateTime start, OffsetDateTime end, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO area_event_schedules (id, area_id, start_at, end_at, status, reason_code, reason_label, note, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'OTHER', 'Khác', 'Lịch dữ liệu test 5b', ?)",
                id, area.getId(), start, end, status, fm.getId());
        return id;
    }

    @Test
    @DisplayName("ES-S3 (BR-ES-S3): GET lịch trả trạng thái đã lưu (COMPLETED, ENDED_EARLY, STARTED quá giờ), không tính theo giờ xem")
    void esS3_BR_ES_S3_getSchedulesReturnsStoredStatus() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        UUID completed = insertSchedule(area, now.minusDays(2), now.minusDays(2).plusHours(2), "COMPLETED");
        UUID endedEarly = insertSchedule(area, now.minusDays(1), now.minusDays(1).plusHours(2), "ENDED_EARLY");
        UUID startedPast = insertSchedule(area, now.minusHours(5), now.minusHours(3), "STARTED");

        MvcResult r = send(get("/api/areas/{id}/event-schedules", area.getId()), fm, null).andReturn();
        assertEquals(200, status(r), describe(r));

        Map<String, String> byId = new HashMap<>();
        for (JsonNode n : json(r).path("data")) {
            byId.put(n.path("id").asText(), n.path("status").asText());
        }
        assertEquals("COMPLETED", byId.get(completed.toString()));
        assertEquals("ENDED_EARLY", byId.get(endedEarly.toString()));
        assertEquals("STARTED", byId.get(startedPast.toString()), "STARTED quá giờ vẫn trả STARTED đã lưu");
    }
}
