package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X-01a (Lucas chốt phương án b): chế độ sự kiện chỉ mở qua lịch (job kích hoạt) và chỉ đóng khi hết giờ hoặc FM bấm Dừng.
 * PATCH /api/areas/{id}/event-mode chỉ nhận DISABLE; ENABLE / ADJUST -> 400 ERR_AREA_058, không đổi dữ liệu, không audit.
 * Kiểm cũ giữ nguyên: có khoá enabled -> 046, thiếu action -> 047.
 */
class EventModeScheduleOnlyTest extends Step5bTestSupport {

    private static final String ERR_058_MESSAGE = "Không bật hoặc điều chỉnh sự kiện thủ công. Hãy đặt lịch ở màn Quản lý sự kiện.";

    private List<AreaEventSession> openSessions(Area area) {
        return sessionRepository.findAll().stream()
                .filter(s -> s.getArea() != null && area.getId().equals(s.getArea().getId()) && s.getActualEnd() == null)
                .toList();
    }

    @Test
    @DisplayName("X-01a: ENABLE thủ công -> 400 ERR_AREA_058, khu không mở, không phiên, không audit, không thông báo GUARD")
    void manualEnable_rejected() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_058", errorCode(r));
        assertEquals(ERR_058_MESSAGE, message(r));
        assertFalse(Boolean.TRUE.equals(reload(area).getOpenToMembers()));
        assertTrue(openSessions(area).isEmpty());
        assertEquals(auditBefore, auditCountForArea(area));
        assertTrue(notificationsOf(guard, com.fa26se040.icss.enums.NotificationType.EVENT_MODE_CHANGED).stream()
                .noneMatch(n -> area.getId().equals(n.getReferenceId())));
    }

    @Test
    @DisplayName("X-01a: ADJUST trên phiên đang chạy (mở qua lịch) -> 400 ERR_AREA_058, giờ kết thúc giữ nguyên, không audit mới")
    void manualAdjust_rejected() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        openEventViaSchedule(area, now.minusMinutes(5), now.plusHours(2));
        OffsetDateTime untilBefore = reload(area).getOpenUntil();
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ADJUST", now.plusHours(4), "EVENT_PROLONGED", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_058", errorCode(r));
        assertEquals(untilBefore.toInstant(), reload(area).getOpenUntil().toInstant());
        assertEquals(1, openSessions(area).size());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    @Test
    @DisplayName("Kiểm cũ giữ nguyên: có khoá enabled -> 400 ERR_AREA_046; thiếu action -> 400 ERR_AREA_047")
    void enabledFieldAndMissingAction_stillRejected() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);

        Map<String, Object> withEnabled = eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, apiVersion(area));
        withEnabled.put("enabled", true);
        MvcResult r046 = patchEventMode(fm, area, withEnabled);
        MvcResult r047 = patchEventMode(fm, area, eventBody(null, null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(400, status(r046), describe(r046));
        assertEquals("ERR_AREA_046", errorCode(r046));
        assertEquals(400, status(r047), describe(r047));
        assertEquals("ERR_AREA_047", errorCode(r047));
    }

    @Test
    @DisplayName("X-01a: lịch tới giờ -> job kích hoạt mở phiên, lịch STARTED; FM Dừng (DISABLE) -> 200, phiên đóng, lịch ENDED_EARLY, khu hết mở")
    void scheduleOpens_fmDisableEndsEarly() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        AreaEventSchedule schedule = newSchedule(area, now.minusMinutes(1), now.plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");

        // Job kích hoạt lịch đến giờ (đường job thật)
        areaService.processScheduledEventActivations(OffsetDateTime.now());

        AreaEventSchedule started = eventScheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(AreaEventScheduleStatus.STARTED, started.getStatus());
        assertTrue(Boolean.TRUE.equals(reload(area).getOpenToMembers()));
        List<AreaEventSession> open = openSessions(area);
        assertEquals(1, open.size());
        assertEquals(schedule.getId(), open.get(0).getScheduleId());

        MvcResult stop = patchEventMode(fm, area, eventBody("DISABLE", null, "ENDED_EARLY", EVENT_NOTE, apiVersion(area)));

        assertEquals(200, status(stop), describe(stop));
        assertFalse(Boolean.TRUE.equals(reload(area).getOpenToMembers()));
        assertTrue(openSessions(area).isEmpty());
        assertNotNull(sessionRepository.findById(open.get(0).getId()).orElseThrow().getActualEnd());
        assertEquals(AreaEventScheduleStatus.ENDED_EARLY, eventScheduleRepository.findById(schedule.getId()).orElseThrow().getStatus());
        assertEquals(1, auditsWithAction(area, "DISABLE_EVENT_MODE").size());
    }
}
