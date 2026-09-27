package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.EventScheduleCancelRequest;
import com.fa26se040.icss.dto.area.EventScheduleRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Step 5b — nhóm lý do riêng cho lịch sự kiện (BR-ES-L1..L3). Test viết trước, phải ĐỎ.
 * Tên nhóm đề xuất (chờ Lucas chốt): EVENT_SCHEDULE_CREATE / EVENT_SCHEDULE_UPDATE / EVENT_SCHEDULE_CANCEL.
 * Mục lý do test tạo qua JPA và được xoá trong finally.
 */
public class Step5bScheduleReasonTest extends Step5bTestSupport {

    private static final String GROUP_CREATE = "EVENT_SCHEDULE_CREATE";
    private static final String GROUP_UPDATE = "EVENT_SCHEDULE_UPDATE";
    private static final String GROUP_CANCEL = "EVENT_SCHEDULE_CANCEL";

    private String code(String prefix) {
        return (prefix + "_" + suffix).toUpperCase();
    }

    private void deleteReasons(List<ReasonCatalog> items) {
        for (ReasonCatalog item : items) {
            if (item != null && item.getId() != null) {
                reasonCatalogRepository.deleteById(item.getId());
            }
        }
    }

    private long schedulesOf(Area area) {
        return eventScheduleRepository.findByAreaIdOrderByStartAtAsc(area.getId()).size();
    }

    private AreaEventSchedule pendingSchedule(Area area) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);
        return newSchedule(area, start, start.plusHours(2), "OTHER", "Khác");
    }

    // ================================================================== ES-L1

    @Test
    @DisplayName("ES-L1 (BR-ES-L1): 3 nhóm lý do lịch tồn tại, mỗi nhóm có mục OTHER")
    void esL1_BR_ES_L1_scheduleGroupsExistWithOther() throws Exception {
        MvcResult r = send(get("/api/reason-catalogs"), admin, null).andReturn();
        assertEquals(200, status(r), describe(r));

        Map<String, List<JsonNode>> byGroup = new HashMap<>();
        for (JsonNode item : json(r).path("data")) {
            byGroup.computeIfAbsent(item.path("actionType").asText(), k -> new ArrayList<>()).add(item);
        }
        for (String group : List.of(GROUP_CREATE, GROUP_UPDATE, GROUP_CANCEL)) {
            List<JsonNode> items = byGroup.getOrDefault(group, List.of());
            assertFalse(items.isEmpty(), "Nhóm lý do " + group + " phải tồn tại");
            assertTrue(items.stream().anyMatch(i -> i.path("isOther").asBoolean(false) && "OTHER".equals(i.path("code").asText())),
                    "Nhóm " + group + " phải có mục OTHER");
        }
    }

    @Test
    @DisplayName("ES-L1 (BR-ES-L1): ADMIN thêm / sửa nhãn / ngừng dùng mục trong nhóm lịch được, có audit")
    void esL1_BR_ES_L1_adminManagesScheduleGroupItem() throws Exception {
        String itemCode = code("ESL1");
        UUID id = null;
        try {
            MvcResult created = send(post("/api/reason-catalogs"), admin, Map.of(
                    "actionType", GROUP_CREATE, "code", itemCode, "label", "Lịch hội thảo test 5b", "sortOrder", 50)).andReturn();
            assertEquals(201, status(created), describe(created));
            id = UUID.fromString(json(created).path("data").path("id").asText());

            MvcResult updated = send(put("/api/reason-catalogs/{id}", id), admin,
                    Map.of("label", "Lịch hội thảo test 5b (đã sửa)", "sortOrder", 60)).andReturn();
            assertEquals(200, status(updated), describe(updated));

            MvcResult deactivated = send(patch("/api/reason-catalogs/{id}/deactivate", id), admin, null).andReturn();
            assertEquals(200, status(deactivated), describe(deactivated));

            ReasonCatalog stored = reasonCatalogRepository.findById(id).orElseThrow();
            assertEquals(GROUP_CREATE, stored.getActionType());
            assertEquals("Lịch hội thảo test 5b (đã sửa)", stored.getLabel());
            assertFalse(stored.getIsActive());
            assertEquals(3, auditsForTarget(id.toString()).size(), "Tạo + sửa + ngừng dùng -> 3 audit");
        } finally {
            if (id != null) {
                reasonCatalogRepository.deleteById(id);
            }
        }
    }

    // ================================================================== ES-L2

    @Test
    @DisplayName("ES-L2 (BR-ES-L2): tạo lịch với mã thuộc nhóm EVENT_ENABLE -> 400 ERR_AREA_026, không tạo lịch, không audit")
    void esL2_BR_ES_L2_createWithEventEnableCode_badRequest026() throws Exception {
        ReasonCatalog enableOnly = newReason("EVENT_ENABLE", code("ESL2EN"), "Lý do bật test 5b");
        try {
            Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
            long auditBefore = auditCountForArea(area);
            OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);

            MvcResult r = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                    new EventScheduleRequest(start, start.plusHours(2), enableOnly.getCode(), EVENT_NOTE)).andReturn();

            assertEquals(400, status(r), describe(r));
            assertEquals("ERR_AREA_026", errorCode(r));
            assertEquals(0, schedulesOf(area));
            assertEquals(auditBefore, auditCountForArea(area));
            assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).isEmpty());
        } finally {
            deleteReasons(List.of(enableOnly));
        }
    }

    @Test
    @DisplayName("ES-L2 (BR-ES-L2): sửa lịch với mã thuộc nhóm EVENT_EXTEND -> 400 ERR_AREA_026, lịch không đổi, không audit")
    void esL2_BR_ES_L2_updateWithEventExtendCode_badRequest026() throws Exception {
        ReasonCatalog extendOnly = newReason("EVENT_EXTEND", code("ESL2EX"), "Lý do gia hạn test 5b");
        try {
            Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
            AreaEventSchedule schedule = pendingSchedule(area);
            long auditBefore = auditCountForArea(area);

            MvcResult r = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), schedule.getId()), fm,
                    new EventScheduleRequest(schedule.getStartAt().plusHours(1), schedule.getEndAt().plusHours(1),
                            extendOnly.getCode(), EVENT_NOTE)).andReturn();

            assertEquals(400, status(r), describe(r));
            assertEquals("ERR_AREA_026", errorCode(r));
            AreaEventSchedule after = eventScheduleRepository.findById(schedule.getId()).orElseThrow();
            assertEquals(schedule.getStartAt().toInstant(), after.getStartAt().toInstant());
            assertEquals("OTHER", after.getReasonCode());
            assertEquals(auditBefore, auditCountForArea(area));
        } finally {
            deleteReasons(List.of(extendOnly));
        }
    }

    @Test
    @DisplayName("ES-L2 (BR-ES-L2): huỷ lịch với ENDED_EARLY (nhóm EVENT_DISABLE) -> 400 ERR_AREA_026, lịch vẫn SCHEDULED, không audit")
    void esL2_BR_ES_L2_cancelWithEndedEarly_badRequest026() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule schedule = pendingSchedule(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = send(post("/api/areas/{id}/event-schedules/{sid}/cancel", area.getId(), schedule.getId()), fm,
                new EventScheduleCancelRequest("ENDED_EARLY", "Huỷ lịch dữ liệu test 5b")).andReturn();

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_026", errorCode(r));
        assertEquals(AreaEventScheduleStatus.SCHEDULED, eventScheduleRepository.findById(schedule.getId()).orElseThrow().getStatus());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("ES-L2 (BR-ES-L2): mã đúng nhóm lịch (tạo / sửa / huỷ) -> 2xx, audit ghi nhãn của nhóm lịch")
    void esL2_BR_ES_L2_scheduleGroupCodes_success_auditRecordsLabel() throws Exception {
        List<ReasonCatalog> created = new ArrayList<>();
        try {
            ReasonCatalog createReason = newReason(GROUP_CREATE, code("ESL2C"), "Lý do đặt lịch test 5b");
            created.add(createReason);
            ReasonCatalog updateReason = newReason(GROUP_UPDATE, code("ESL2U"), "Lý do sửa lịch test 5b");
            created.add(updateReason);
            ReasonCatalog cancelReason = newReason(GROUP_CANCEL, code("ESL2X"), "Lý do huỷ lịch test 5b");
            created.add(cancelReason);

            Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
            OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);

            MvcResult c = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                    new EventScheduleRequest(start, start.plusHours(2), createReason.getCode(), EVENT_NOTE)).andReturn();
            assertEquals(201, status(c), describe(c));
            UUID scheduleId = UUID.fromString(json(c).path("data").path("id").asText());
            assertEquals(createReason.getLabel(), json(c).path("data").path("reasonLabel").asText());

            MvcResult u = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), scheduleId), fm,
                    new EventScheduleRequest(start.plusHours(1), start.plusHours(3), updateReason.getCode(), EVENT_NOTE)).andReturn();
            assertEquals(200, status(u), describe(u));

            MvcResult x = send(post("/api/areas/{id}/event-schedules/{sid}/cancel", area.getId(), scheduleId), fm,
                    new EventScheduleCancelRequest(cancelReason.getCode(), "Huỷ lịch dữ liệu test 5b")).andReturn();
            assertEquals(200, status(x), describe(x));
            assertEquals(cancelReason.getLabel(), json(x).path("data").path("cancelReasonLabel").asText());

            Map<String, String> labelByAction = new HashMap<>();
            for (AuditLog a : auditsForTarget(scheduleId.toString())) {
                labelByAction.put(a.getAction().name(), objectMapper.readTree(a.getNewValue()).path("reasonLabel").asText());
            }
            assertEquals(createReason.getLabel(), labelByAction.get("CREATE"), "Audit tạo lịch ghi nhãn nhóm lịch");
            assertEquals(updateReason.getLabel(), labelByAction.get("UPDATE"), "Audit sửa lịch ghi nhãn nhóm lịch");
            assertEquals(cancelReason.getLabel(), labelByAction.get("CANCEL"), "Audit huỷ lịch ghi nhãn nhóm lịch");
        } finally {
            deleteReasons(created);
        }
    }

    // ================================================================== ES-L2b

    @Test
    @DisplayName("ES-L2b (BR-ES-L2): lịch cũ tạo bằng lý do nhóm cũ -> GET vẫn trả nhãn đã lưu")
    void esL2b_BR_ES_L2_legacyScheduleKeepsStoredLabel() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);
        String storedLabel = "Nhãn cũ đã lưu " + suffix;
        AreaEventSchedule legacy = newSchedule(area, start, start.plusHours(2), "SEMINAR", storedLabel);

        MvcResult r = send(get("/api/areas/{id}/event-schedules", area.getId()), fm, null).andReturn();

        assertEquals(200, status(r), describe(r));
        JsonNode found = null;
        for (JsonNode item : json(r).path("data")) {
            if (legacy.getId().toString().equals(item.path("id").asText())) {
                found = item;
            }
        }
        assertNotNull(found, "GET phải trả lịch cũ");
        assertEquals("SEMINAR", found.path("reasonCode").asText());
        assertEquals(storedLabel, found.path("reasonLabel").asText(), "Phải trả đúng nhãn đã lưu, không tra lại danh mục");
    }

    // ================================================================== ES-L3

    @Test
    @DisplayName("ES-L3 (BR-ES-L3): lịch tự kích hoạt -> audit ENABLE actor SYSTEM ghi lý do đặt lịch gốc")
    void esL3_BR_ES_L3_autoActivation_auditKeepsOriginalReason() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        String originalCode = code("ESL3");
        String originalLabel = "Lý do đặt lịch gốc " + suffix;
        AreaEventSchedule schedule = newSchedule(area, now.minusMinutes(1), now.plusHours(2), originalCode, originalLabel);

        areaService.activateScheduleInTx(schedule.getId(), now);

        assertEquals(AreaEventScheduleStatus.STARTED, eventScheduleRepository.findById(schedule.getId()).orElseThrow().getStatus());
        List<AuditLog> enable = auditsWithAction(area, "ENABLE_EVENT_MODE");
        assertEquals(1, enable.size());
        AuditLog audit = enable.get(0);
        assertEquals("SYSTEM", audit.getActorType());
        JsonNode newValue = objectMapper.readTree(audit.getNewValue());
        assertEquals(originalCode, newValue.path("reasonCode").asText());
        assertEquals(originalLabel, newValue.path("reasonLabel").asText());
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }
}
