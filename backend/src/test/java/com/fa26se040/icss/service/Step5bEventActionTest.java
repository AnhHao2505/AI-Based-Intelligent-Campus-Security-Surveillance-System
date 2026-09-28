package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 5b — ý định thao tác chế độ sự kiện (BR-EV-A1..A7, H1, H3). Test viết trước, phải ĐỎ.
 * Hợp đồng: body {action: ENABLE|ADJUST|DISABLE, openUntil, reasonCode, note, version}; trường "enabled" bị cấm.
 */
public class Step5bEventActionTest extends Step5bTestSupport {

    private long eventAudits(Area area) {
        return auditsForArea(area).stream().filter(a -> a.getTargetType() == AuditTargetType.AREA_EVENT_MODE).count();
    }

    private List<AreaEventSession> openSessions(Area area) {
        return sessionRepository.findByAreaId(area.getId()).stream().filter(s -> s.getActualEnd() == null).toList();
    }

    private void assertNothingHappened(Area area, long eventAuditsBefore, boolean expectOpen) {
        Area after = reload(area);
        assertEquals(expectOpen, after.isEventActive(OffsetDateTime.now()), "Trạng thái sự kiện không được đổi");
        assertEquals(eventAuditsBefore, eventAudits(area), "Bị chặn thì không ghi audit");
        assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty(), "Bị chặn thì không thông báo GUARD");
    }

    // ================================================================== EV-A1

    @Test
    @DisplayName("EV-A1 (BR-EV-A1): thiếu action -> 400 ERR_AREA_047, không đổi gì")
    void evA1_BR_EV_A1_missingAction_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody(null, OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_047", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A1 (BR-EV-A1): action lạ -> 400, không đổi gì")
    void evA1_BR_EV_A1_unknownAction_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("TOGGLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r), describe(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A1 (BR-EV-A1): body có \"enabled\" kèm action hợp lệ -> 400 ERR_AREA_046, không đổi gì")
    void evA1_BR_EV_A1_enabledWithValidAction_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);
        Map<String, Object> body = eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area));
        body.put("enabled", true);

        MvcResult r = patchEventMode(fm, area, body);

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_046", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A1 (BR-EV-A1): body chỉ có \"enabled\" (màn cũ) -> 400 ERR_AREA_046, không đổi gì")
    void evA1_BR_EV_A1_enabledOnly_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);
        Map<String, Object> body = eventBody(null, OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area));
        body.put("enabled", true);

        MvcResult r = patchEventMode(fm, area, body);

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_046", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A1b (BR-EV-A1): JSON sai cú pháp / sai kiểu (openUntil, version) -> 400, không 500, không đổi gì")
    void evA1b_BR_EV_A1_malformedOrWrongTypeJson_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);
        Long v = apiVersion(area);

        MvcResult broken = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/areas/{id}/event-mode", area.getId())
                        .header("Authorization", bearer(fm))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"ENABLE\", \"note\": "))
                .andReturn();
        assertEquals(400, status(broken), "JSON sai cú pháp: " + describe(broken));

        Map<String, Object> badDate = eventBody("ENABLE", null, "SEMINAR", EVENT_NOTE, v);
        badDate.put("openUntil", "ngày mai");
        MvcResult r1 = patchEventMode(fm, area, badDate);
        assertEquals(400, status(r1), "openUntil sai kiểu: " + describe(r1));

        Map<String, Object> badVersion = eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, null);
        badVersion.put("version", "abc");
        MvcResult r2 = patchEventMode(fm, area, badVersion);
        assertEquals(400, status(r2), "version sai kiểu: " + describe(r2));

        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A5 (BR-EV-A5): khu vực chưa từng mở sự kiện -> 030 \"đang tắt\" (dạng thứ 4)")
    void evA5_BR_EV_A5_message_neverOpened_off() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);

        MvcResult r = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertEquals("Trạng thái sự kiện đã thay đổi: đang tắt. Vui lòng tải lại trang.", message(r));
    }

    // ================================================================== EV-A2

    @Test
    @DisplayName("EV-A2 (BR-EV-A2, ca K8a): ENABLE + ENDED_EARLY (lý do nhóm tắt) -> 400 ERR_AREA_026, không đổi gì")
    void evA2_BR_EV_A2_reasonOfOtherGroup_badRequest026() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_026", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A2 (BR-EV-A2): OTHER tra đúng nhóm theo action (ENABLE / ADJUST / DISABLE)")
    void evA2_BR_EV_A2_otherResolvedByActionGroup() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();

        MvcResult enable = patchEventMode(fm, area, eventBody("ENABLE", now.plusHours(2), "OTHER", EVENT_NOTE, apiVersion(area)));
        assertEquals(200, status(enable), "ENABLE + OTHER: " + describe(enable));
        MvcResult adjust = patchEventMode(fm, area, eventBody("ADJUST", now.plusHours(3), "OTHER", EVENT_NOTE, apiVersion(area)));
        assertEquals(200, status(adjust), "ADJUST + OTHER: " + describe(adjust));
        MvcResult disable = patchEventMode(fm, area, eventBody("DISABLE", null, "OTHER", EVENT_NOTE, apiVersion(area)));
        assertEquals(200, status(disable), "DISABLE + OTHER: " + describe(disable));

        List<String> actions = auditsForArea(area).stream()
                .filter(a -> a.getTargetType() == AuditTargetType.AREA_EVENT_MODE)
                .sorted(Comparator.comparing(AuditLog::getChangedAt))
                .map(a -> a.getAction().name())
                .toList();
        assertEquals(List.of("ENABLE_EVENT_MODE", "EXTEND_EVENT_MODE", "DISABLE_EVENT_MODE"), actions);
        assertFalse(reload(area).isEventActive(OffsetDateTime.now()));
    }

    // ================================================================== EV-A3

    @Test
    @DisplayName("EV-A3 (BR-EV-A3): ENABLE khi đang mở -> 409 ERR_AREA_030, giờ kết thúc giữ nguyên")
    void evA3_BR_EV_A3_enableWhileOpen_conflict030() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(1));
        OffsetDateTime until = reload(area).getOpenUntil();
        long before = eventAudits(area);

        // OTHER có trong cả 3 nhóm: chỉ ý định (action) mới phân biệt được ENABLE với DISABLE (ca K8 / H1)
        MvcResult r = patchEventMode(fm, area, eventBody("ENABLE", now.plusHours(3), "OTHER", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertNothingHappened(area, before, true);
        assertEquals(until.toInstant(), reload(area).getOpenUntil().toInstant());
    }

    @Test
    @DisplayName("EV-A3 (BR-EV-A3): ADJUST khi đang tắt -> 409 ERR_AREA_030")
    void evA3_BR_EV_A3_adjustWhileOff_conflict030() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", OffsetDateTime.now().plusHours(2), "EVENT_PROLONGED", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    @Test
    @DisplayName("EV-A3 (BR-EV-A3): DISABLE khi đang tắt -> 409 ERR_AREA_030")
    void evA3_BR_EV_A3_disableWhileOff_conflict030() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertNothingHappened(area, before, false);
    }

    // ================================================================== EV-A4

    /** Đang mở sự kiện; trả version cũ v0 (đã bị một lần sửa quy tắc truy cập làm cũ). */
    private Long openAreaWithStaleVersion(Area area) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        openEvent(area, now.minusMinutes(10), now.plusHours(1));
        Long v0 = apiVersion(area);
        MvcResult bump = patchAccessRules(fm2, area, 2, true, v0);
        assertEquals(200, status(bump), "Tiền đề: sửa quy tắc để version tăng: " + describe(bump));
        return v0;
    }

    @Test
    @DisplayName("EV-A4 (BR-EV-A4): dữ liệu sai + version cũ -> 400 (kiểm dữ liệu trước version), không audit, không thông báo")
    void evA4_BR_EV_A4_invalidDataAndStaleVersion_badRequestFirst() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = openAreaWithStaleVersion(area);
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", OffsetDateTime.now().plusHours(2), "EVENT_PROLONGED", "ngan", v0));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_024", errorCode(r));
        assertNothingHappened(area, before, true);
    }

    @Test
    @DisplayName("EV-A4 (BR-EV-A4): version cũ + ý định lệch (ENABLE khi đang mở) -> 409 ERR_AREA_045, không audit, không thông báo")
    void evA4_BR_EV_A4_staleVersionAndIntentMismatch_versionConflictFirst() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = openAreaWithStaleVersion(area);
        OffsetDateTime until = reload(area).getOpenUntil();
        long before = eventAudits(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, v0));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_045", errorCode(r), "Lệch version phải được báo trước lệch ý định");
        assertNothingHappened(area, before, true);
        assertEquals(until.toInstant(), reload(area).getOpenUntil().toInstant());
    }

    // ================================================================== EV-A5

    @Test
    @DisplayName("EV-A5 (BR-EV-A5): câu 030 khi đang mở -> \"đang mở đến X\"")
    void evA5_BR_EV_A5_message_whileOpen() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(1));
        OffsetDateTime until = reload(area).getOpenUntil();

        // OTHER có trong cả 3 nhóm: chỉ ý định (action) mới phân biệt được ENABLE với DISABLE (ca K8 / H1)
        MvcResult r = patchEventMode(fm, area, eventBody("ENABLE", now.plusHours(3), "OTHER", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertEquals("Trạng thái sự kiện đã thay đổi: đang mở đến " + VN_TIME.format(until) + ". Vui lòng tải lại trang.", message(r));
    }

    @Test
    @DisplayName("EV-A5 (BR-EV-A5): câu 030 sau khi tắt tay -> giờ actual_end")
    void evA5_BR_EV_A5_message_afterManualDisable_usesActualEnd() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime actualEnd = now.minusHours(1);
        sessionRepository.save(AreaEventSession.builder()
                .area(area).startedAt(now.minusHours(3)).plannedEnd(now.plusHours(1)).actualEnd(actualEnd)
                .startedBy(fm).endedBy(fm).createdAt(now.minusHours(3)).build());

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", now.plusHours(2), "EVENT_PROLONGED", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertEquals("Trạng thái sự kiện đã thay đổi: đã tắt lúc " + VN_TIME.format(actualEnd) + ". Vui lòng tải lại trang.", message(r));
    }

    @Test
    @DisplayName("EV-A5 (BR-EV-A5): câu 030 sau khi hết hạn -> giờ planned_end")
    void evA5_BR_EV_A5_message_afterExpiry_usesPlannedEnd() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime plannedEnd = now.minusHours(1);
        sessionRepository.save(AreaEventSession.builder()
                .area(area).startedAt(now.minusHours(3)).plannedEnd(plannedEnd).actualEnd(plannedEnd)
                .startedBy(fm).createdAt(now.minusHours(3)).build());

        MvcResult r = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertEquals("Trạng thái sự kiện đã thay đổi: đã hết hạn lúc " + VN_TIME.format(plannedEnd) + ". Vui lòng tải lại trang.", message(r));
    }

    // ================================================================== EV-A6

    @Test
    @DisplayName("EV-A6 (BR-EV-A6): quá open_until, job chưa đóng -> ADJUST -> 409 ERR_AREA_030 \"đã hết hạn\"")
    void evA6_BR_EV_A6_adjustAfterOpenUntil_conflictExpired() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession expired = openEvent(area, now.minusHours(2), now.minusMinutes(5));

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", now.plusHours(1), "EVENT_PROLONGED", EVENT_NOTE, apiVersion(area)));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_030", errorCode(r));
        assertEquals("Trạng thái sự kiện đã thay đổi: đã hết hạn lúc " + VN_TIME.format(expired.getPlannedEnd())
                + ". Vui lòng tải lại trang.", message(r));
        assertEquals(0, auditsWithAction(area, "EXTEND_EVENT_MODE").size());
    }

    @Test
    @DisplayName("EV-A6 (BR-EV-A6): quá open_until, job chưa đóng -> ENABLE -> phiên mới, giờ bắt đầu mới")
    void evA6_BR_EV_A6_enableAfterOpenUntil_newSession() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession old = openEvent(area, now.minusHours(2), now.minusMinutes(5));
        OffsetDateTime until = now.plusHours(2).withNano(0);

        MvcResult r = patchEventMode(fm, area, eventBody("ENABLE", until, "SEMINAR", EVENT_NOTE, apiVersion(area)));

        assertEquals(200, status(r), describe(r));
        List<AreaEventSession> open = openSessions(area);
        assertEquals(1, open.size(), "Đúng 1 phiên đang mở");
        AreaEventSession fresh = open.get(0);
        assertNotEquals(old.getId(), fresh.getId(), "Phải là phiên mới");
        assertFalse(fresh.getStartedAt().isBefore(now.minusSeconds(1)), "Giờ bắt đầu mới (không lấy giờ phiên cũ)");
        assertEquals(until.toInstant(), fresh.getPlannedEnd().toInstant());
        assertEquals(1, auditsWithAction(area, "ENABLE_EVENT_MODE").size());
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }

    // ================================================================== EV-A7

    @Test
    @DisplayName("EV-A7 (BR-EV-A7): gửi 2 lần ENABLE cùng version, 10 vòng -> 1 thành công, 1 nhận 409 ERR_AREA_045; đúng 1 phiên mở")
    void evA7_BR_EV_A7_doubleSubmitSameVersion_exactlyOneSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
                Long version = areaService.getAreaById(area.getId()).version();
                OffsetDateTime until = OffsetDateTime.now().plusHours(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger success = new AtomicInteger();
                AtomicInteger stale = new AtomicInteger();
                List<Throwable> unexpected = new CopyOnWriteArrayList<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        areaService.updateEventMode(area.getId(),
                                new AreaEventModeUpdateRequest(null, until, "SEMINAR", EVENT_NOTE, EventModeAction.ENABLE, version),
                                fm.getEmail());
                        success.incrementAndGet();
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
                };
                executor.submit(task);
                executor.submit(task);
                start.countDown();
                assertTrue(done.await(20, TimeUnit.SECONDS), "Vòng " + round + ": quá thời gian");

                assertEquals(1, success.get(), "Vòng " + round + ": đúng 1 lần thành công (unexpected=" + unexpected + ")");
                assertEquals(1, stale.get(), "Vòng " + round + ": đúng 1 lần nhận ERR_AREA_045 (unexpected=" + unexpected + ")");
                assertEquals(1, openSessions(area).size(), "Vòng " + round + ": đúng 1 phiên mở");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ================================================================== H1, H3

    @Test
    @DisplayName("H1 (BR-EV-A4, BR-EV-A7): A thấy đang tắt, gửi ENABLE + OTHER; B đã bật trước -> A nhận 409, giờ kết thúc của B giữ nguyên")
    void h1_BR_EV_A4_otherEnableAfterConcurrentEnable_conflictKeepsB() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long seenByA = apiVersion(area);
        OffsetDateTime untilB = OffsetDateTime.now().plusHours(2).withNano(0);

        MvcResult b = patchEventMode(fm2, area, eventBody("ENABLE", untilB, "SEMINAR", EVENT_NOTE, seenByA));
        assertEquals(200, status(b), "B bật trước: " + describe(b));
        MvcResult a = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(5), "OTHER", EVENT_NOTE, seenByA));

        assertEquals(409, status(a), describe(a));
        assertEquals("ERR_AREA_045", errorCode(a));
        Area after = reload(area);
        assertTrue(after.isEventActive(OffsetDateTime.now()));
        assertEquals(untilB.toInstant(), after.getOpenUntil().toInstant(), "Giờ kết thúc của B giữ nguyên");
        assertEquals(1, openSessions(area).size());
        assertEquals(1, auditsWithAction(area, "ENABLE_EVENT_MODE").size());
        assertEquals(0, auditsWithAction(area, "EXTEND_EVENT_MODE").size() + auditsWithAction(area, "DISABLE_EVENT_MODE").size());
    }

    @Test
    @DisplayName("H3 (BR-EV-A4): hai ADJUST cùng version -> người sau 409 ERR_AREA_045, giờ của người trước giữ nguyên")
    void h3_BR_EV_A4_twoAdjustSameVersion_secondConflicts() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(1));
        Long seen = apiVersion(area);
        OffsetDateTime untilA = now.plusHours(2).withNano(0);

        MvcResult a = patchEventMode(fm, area, eventBody("ADJUST", untilA, "EVENT_PROLONGED", EVENT_NOTE, seen));
        assertEquals(200, status(a), "A điều chỉnh trước: " + describe(a));
        MvcResult b = patchEventMode(fm2, area, eventBody("ADJUST", now.plusHours(3), "EVENT_PROLONGED", EVENT_NOTE, seen));

        assertEquals(409, status(b), describe(b));
        assertEquals("ERR_AREA_045", errorCode(b));
        assertEquals(untilA.toInstant(), reload(area).getOpenUntil().toInstant(), "Giờ của người trước giữ nguyên");
        assertEquals(1, auditsWithAction(area, "EXTEND_EVENT_MODE").size());
        JsonNode lastAdjust = json(a).path("data");
        assertEquals(untilA.toInstant(), OffsetDateTime.parse(lastAdjust.path("openUntil").asText()).toInstant());
    }
}
