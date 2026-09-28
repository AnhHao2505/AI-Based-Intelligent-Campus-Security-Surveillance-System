package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 5b phần C (BR-ES-S4): backfill trạng thái lịch STARTED có từ trước V56.
 * Dữ liệu "cũ" = lịch STARTED trỏ session_id tới phiên đầu, các phiên KHÔNG có schedule_id (ADJUST trước V56 chưa nối lịch).
 * Test đọc file migration từ classpath và thực thi qua JdbcTemplate (không phụ thuộc lần Flyway đã chạy).
 */
public class Step5bScheduleBackfillTest extends Step5bTestSupport {

    private static final String MIGRATION = "db/migration/V56_1__backfill_legacy_started_schedules.sql";

    private void runBackfill() {
        new ResourceDatabasePopulator(new ClassPathResource(MIGRATION)).execute(dataSource);
    }

    /** Phiên "cũ": không có schedule_id. */
    private AreaEventSession legacySession(Area area, OffsetDateTime startedAt, OffsetDateTime plannedEnd,
                                           OffsetDateTime actualEnd, boolean endedByFm) {
        return sessionRepository.save(AreaEventSession.builder()
                .area(area)
                .startedAt(startedAt)
                .plannedEnd(plannedEnd)
                .actualEnd(actualEnd)
                .startedBy(fm)
                .endedBy(endedByFm ? fm : null)
                .createdAt(startedAt)
                .scheduleId(null)
                .build());
    }

    /** Lịch STARTED "cũ" trỏ tới phiên đầu (hoặc không trỏ phiên nào). */
    private AreaEventSchedule legacyStartedSchedule(Area area, OffsetDateTime start, OffsetDateTime end, AreaEventSession first) {
        return eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(area)
                .startAt(start)
                .endAt(end)
                .status(AreaEventScheduleStatus.STARTED)
                .reasonCode("OTHER")
                .reasonLabel("Khác")
                .note("Lịch dữ liệu cũ test BR-ES-S4")
                .createdBy(fm)
                .session(first)
                .build());
    }

    private String status(AreaEventSchedule s) {
        return eventScheduleRepository.findById(s.getId()).orElseThrow().getStatus().name();
    }

    @Test
    @DisplayName("BR-ES-S4: backfill lịch STARTED cũ — (a) hết giờ -> COMPLETED; (b) tắt sớm -> ENDED_EARLY; (c) chuỗi ADJUST rồi hết giờ -> COMPLETED; (d) còn phiên mở -> STARTED; (e) không có phiên -> STARTED; (f) chạy lần 2 không đổi gì")
    void esS4_BR_ES_S4_backfillLegacyStartedSchedules() {
        OffsetDateTime now = OffsetDateTime.now().withNano(0);

        // (a) phiên duy nhất đóng do hết giờ: actual_end = planned_end
        Area areaA = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession a1 = legacySession(areaA, now.minusHours(3), now.minusHours(1), now.minusHours(1), false);
        AreaEventSchedule schA = legacyStartedSchedule(areaA, now.minusHours(3), now.minusHours(1), a1);

        // (b) FM tắt sớm: actual_end < planned_end
        Area areaB = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession b1 = legacySession(areaB, now.minusHours(3), now.plusHours(1), now.minusHours(1), true);
        AreaEventSchedule schB = legacyStartedSchedule(areaB, now.minusHours(3), now.plusHours(1), b1);

        // (c) chuỗi: phiên do lịch bị ADJUST (đóng tại t1, phiên mới started_at = t1) rồi phiên kéo dài hết giờ
        Area areaC = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime t1 = now.minusHours(3);
        AreaEventSession c1 = legacySession(areaC, now.minusHours(4), now.minusHours(2), t1, true);
        legacySession(areaC, t1, now.minusHours(1), now.minusHours(1), false);
        AreaEventSchedule schC = legacyStartedSchedule(areaC, now.minusHours(4), now.minusHours(2), c1);

        // (d) khu vực còn phiên mở
        Area areaD = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession d1 = legacySession(areaD, now.minusHours(1), now.plusHours(1), null, false);
        AreaEventSchedule schD = legacyStartedSchedule(areaD, now.minusHours(1), now.plusHours(1), d1);

        // (e) lịch STARTED không trỏ phiên nào
        Area areaE = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSchedule schE = legacyStartedSchedule(areaE, now.minusHours(5), now.minusHours(4), null);

        runBackfill();

        Map<String, String> after1 = new LinkedHashMap<>();
        after1.put("a", status(schA));
        after1.put("b", status(schB));
        after1.put("c", status(schC));
        after1.put("d", status(schD));
        after1.put("e", status(schE));
        assertEquals("COMPLETED", after1.get("a"), "(a) hết giờ -> COMPLETED");
        assertEquals("ENDED_EARLY", after1.get("b"), "(b) tắt sớm -> ENDED_EARLY");
        assertEquals("COMPLETED", after1.get("c"), "(c) chuỗi ADJUST rồi hết giờ -> COMPLETED (xét phiên cuối chuỗi, không phải phiên đầu)");
        assertEquals("STARTED", after1.get("d"), "(d) khu vực còn phiên mở -> giữ STARTED");
        assertEquals("STARTED", after1.get("e"), "(e) không có phiên -> giữ nguyên");

        // (f) idempotent: chạy lần 2 không đổi gì
        runBackfill();
        Map<String, String> after2 = new LinkedHashMap<>();
        after2.put("a", status(schA));
        after2.put("b", status(schB));
        after2.put("c", status(schC));
        after2.put("d", status(schD));
        after2.put("e", status(schE));
        assertEquals(after1, after2, "(f) chạy lần 2 không được đổi gì");

        // Chỉ đụng status: phiên giữ nguyên, lịch vẫn trỏ phiên đầu
        assertEquals(a1.getId(), eventScheduleRepository.findById(schA.getId()).orElseThrow().getSession().getId());
        assertNull(sessionRepository.findById(d1.getId()).orElseThrow().getActualEnd(), "Backfill không đóng phiên đang mở");
    }

    @Test
    @DisplayName("BR-ES-S4: backfill chỉ đụng lịch STARTED — lịch CANCELLED / SCHEDULED có phiên liên quan không đổi")
    void esS4_BR_ES_S4_onlyStartedSchedulesTouched() {
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession s = legacySession(area, now.minusHours(3), now.minusHours(1), now.minusHours(1), false);
        AreaEventSchedule cancelled = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(area).startAt(now.minusHours(3)).endAt(now.minusHours(1))
                .status(AreaEventScheduleStatus.CANCELLED).reasonCode("OTHER").reasonLabel("Khác")
                .note("Lịch đã huỷ test BR-ES-S4").createdBy(fm).session(s).build());
        UUID id = cancelled.getId();

        runBackfill();

        assertEquals("CANCELLED", eventScheduleRepository.findById(id).orElseThrow().getStatus().name());
    }
}
