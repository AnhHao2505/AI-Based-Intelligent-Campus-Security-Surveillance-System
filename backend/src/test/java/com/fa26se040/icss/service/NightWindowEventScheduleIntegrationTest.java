package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.EventNightAdjustment;
import com.fa26se040.icss.dto.area.EventScheduleRequest;
import com.fa26se040.icss.dto.area.EventScheduleResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-5 (V115, Lucas chốt 11/11/2026).
 * <ul>
 *   <li>EV-N1 / EV-N2: không còn trần giờ mỗi phiên, ngân sách giờ theo cửa sổ, số lịch chờ tối đa.</li>
 *   <li>EV-N7: lịch [start, end) không được chạm khung ban đêm AI_AFTER_HOUR_START–AI_AFTER_HOUR_END (tạo, sửa, kích hoạt).</li>
 *   <li>EV-N8: ADMIN đổi giờ ban đêm -> cùng transaction cắt / huỷ lịch, cắt / tắt sự kiện đang mở; audit SYSTEM, thông báo.</li>
 * </ul>
 * Thời gian: lịch đặt ở giờ Việt Nam cụ thể của các ngày tới (không phụ thuộc giờ chạy test); sự kiện đang mở dựng với
 * {@code now} truyền vào applyAfterHoursChange. Step5bTestSupport tắt khung ban đêm ở @BeforeEach và trả lại ở @AfterEach;
 * test đặt khung cụ thể khi cần.
 */
class NightWindowEventScheduleIntegrationTest extends Step5bTestSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CREATE_REASON = "SEMINAR";
    private static final String UPDATE_REASON = "INPUT_ERROR";
    private static final String NOTE = "Ghi chú lịch kiểm thử khung ban đêm";

    private Area area;

    private final List<Area> createdAreas = new java.util.ArrayList<>();

    @BeforeEach
    void setUpNight() {
        area = nightArea(AreaLevel.CONFIDENTIAL);
        systemConfigService.update(ConfigKey.EVENT_MODE_MIN_MINUTES.name(), "15", admin.getEmail());
    }

    private Area nightArea(AreaLevel level) {
        Area a = newArea(level);
        createdAreas.add(a);
        return a;
    }

    /** Đóng phiên còn mở của khu do test tạo (phiên dựng ở tương lai), không để sót trên DB test dùng chung. */
    @org.junit.jupiter.api.AfterEach
    void closeOpenSessions() {
        for (Area a : createdAreas) {
            sessionRepository.findByAreaIdAndActualEndIsNull(a.getId()).ifPresent(s -> {
                s.setActualEnd(s.getStartedAt());
                sessionRepository.save(s);
            });
            Area fresh = reload(a);
            fresh.setOpenToMembers(false);
            fresh.setOpenUntil(null);
            areaRepository.save(fresh);
        }
        createdAreas.clear();
    }

    /** Mốc giờ Việt Nam hh:mm của ngày (hôm nay + plusDays). */
    private static OffsetDateTime at(int plusDays, int hour, int minute) {
        return LocalDate.now(VN).plusDays(plusDays).atTime(hour, minute).atZone(VN).toOffsetDateTime();
    }

    /** Đặt khung ban đêm: START trước rồi END (từ trạng thái tắt 06:00–06:00 đi qua khung qua đêm START–06:00). */
    private void nightWindow(String start, String end) {
        if (!start.equals(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START))) {
            systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), start, admin.getEmail());
        }
        if (!end.equals(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END))) {
            systemConfigService.update(ConfigKey.AI_AFTER_HOUR_END.getKey(), end, admin.getEmail());
        }
    }

    private EventScheduleResponse create(Area target, OffsetDateTime start, OffsetDateTime end) {
        return areaService.createSchedule(target.getId(),
                new EventScheduleRequest("Sự kiện ban ngày", start, end, CREATE_REASON, NOTE), fm.getEmail());
    }

    private AreaException createFails(Area target, OffsetDateTime start, OffsetDateTime end) {
        return assertThrows(AreaException.class, () -> create(target, start, end));
    }

    private AreaEventSchedule schedule(UUID id) {
        return eventScheduleRepository.findById(id).orElseThrow();
    }

    private List<AuditLog> afterHoursAudits(String targetId) {
        return auditsForTarget(targetId).stream()
                .filter(a -> AreaService.AFTER_HOURS_CHANGE_SOURCE.equals(a.getActorSource()))
                .toList();
    }

    private List<EventNightAdjustment> ofArea(SystemConfigResponse response, Area target) {
        return response.eventAdjustments().stream().filter(a -> target.getId().equals(a.areaId())).toList();
    }

    // ------------------------------------------------------------------ EV-N1 / EV-N2 / EV-N7: tạo, sửa lịch

    @Test
    @DisplayName("a) Lịch 30 giờ ban ngày qua 2 ngày -> ERR_AREA_059 (EV-N7, không phải trần giờ); 5 lịch 08:00–21:00 (65 giờ / tuần, 13 giờ / lịch) -> OK")
    void a_longDaytimeRangeBlockedByNight_noBudgetNoMaxHours() {
        nightWindow("22:00", "06:00");

        AreaException ex = createFails(area, at(2, 8, 0), at(3, 14, 0));
        assertEquals(AreaErrorCode.ERR_AREA_059, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("22:00–06:00"), ex.getMessage());
        assertTrue(ex.getMessage().contains("08:00 ") && ex.getMessage().contains("14:00 "), "phải nêu khoảng đã nhập: " + ex.getMessage());

        for (int d = 1; d <= 5; d++) {
            assertEquals(AreaEventScheduleStatus.SCHEDULED.name(), create(area, at(d, 8, 0), at(d, 21, 0)).status(), "ngày +" + d);
        }
    }

    @Test
    @DisplayName("b) 10 lịch ban ngày cùng khu không chồng -> OK (bỏ giới hạn số lịch); lịch chồng -> ERR_AREA_037")
    void b_tenSchedulesAllowed_overlapStillBlocked() {
        nightWindow("22:00", "06:00");
        for (int i = 0; i < 10; i++) {
            create(area, at(2, 8 + i, 0), at(2, 8 + i, 45));
        }
        assertEquals(10, eventScheduleRepository.countByAreaIdAndStatus(area.getId(), AreaEventScheduleStatus.SCHEDULED));
        assertEquals(AreaErrorCode.ERR_AREA_037, createFails(area, at(2, 8, 30), at(2, 9, 30)).getErrorCode());
    }

    @Test
    @DisplayName("c) Ban đêm 22:00: lịch 21:00–22:30 -> ERR_AREA_059; 06:00–07:00 với ban đêm hết lúc 06:00 -> OK (biên); 21:00–22:00 -> OK (biên)")
    void c_boundaries() {
        nightWindow("22:00", "06:00");
        AreaException ex = createFails(area, at(2, 21, 0), at(2, 22, 30));
        assertEquals(AreaErrorCode.ERR_AREA_059, ex.getErrorCode());
        assertEquals(AreaEventScheduleStatus.SCHEDULED.name(), create(area, at(3, 6, 0), at(3, 7, 0)).status());
        assertEquals(AreaEventScheduleStatus.SCHEDULED.name(), create(area, at(3, 21, 0), at(3, 22, 0)).status());
    }

    @Test
    @DisplayName("d) Khung KHÔNG qua nửa đêm 20:00–23:00: 19:00–20:30 -> 059; 23:00–23:45 và 00:30–05:00 -> OK; sửa lịch vào 20:00–21:00 -> 059")
    void d_sameDayWindow_createAndUpdate() {
        nightWindow("20:00", "23:00");
        assertEquals(AreaErrorCode.ERR_AREA_059, createFails(area, at(2, 19, 0), at(2, 20, 30)).getErrorCode());
        create(area, at(2, 23, 0), at(2, 23, 45));
        EventScheduleResponse early = create(area, at(3, 0, 30), at(3, 5, 0));

        AreaException ex = assertThrows(AreaException.class, () -> areaService.updateSchedule(area.getId(), early.id(),
                new EventScheduleRequest("Sự kiện ban ngày", at(3, 20, 0), at(3, 21, 0), UPDATE_REASON, NOTE), fm.getEmail()));
        assertEquals(AreaErrorCode.ERR_AREA_059, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("20:00–23:00"), ex.getMessage());
        assertEquals(at(3, 5, 0).toInstant(), schedule(early.id()).getEndAt().toInstant(), "lịch không đổi khi bị từ chối");
    }

    @Test
    @DisplayName("e) Khu Bảo mật cao -> ERR_AREA_022 như cũ (kiểm trước khung ban đêm)")
    void e_highlyConfidentialStillBlocked() {
        nightWindow("22:00", "06:00");
        Area highly = nightArea(AreaLevel.HIGHLY_CONFIDENTIAL);
        assertEquals(AreaErrorCode.ERR_AREA_022, createFails(highly, at(2, 9, 0), at(2, 10, 0)).getErrorCode());
        assertEquals(AreaErrorCode.ERR_AREA_022, createFails(highly, at(2, 21, 0), at(2, 23, 0)).getErrorCode());
    }

    @Test
    @DisplayName("EV-N7 kích hoạt: lịch cũ trong DB chạm ban đêm -> FAILED + audit FAIL SYSTEM + FM nhận EVENT_MODE_SCHEDULE_FAILED; không mở phiên")
    void activation_ofStoredScheduleTouchingNight_fails() {
        // Đặt khung TRƯỚC rồi mới lưu thẳng lịch vi phạm (bỏ qua kiểm khi tạo, như dữ liệu cũ trong DB):
        // đổi khung sau khi lưu thì EV-N8 đã cắt lịch trước khi job kích hoạt
        nightWindow("22:00", "06:00");
        AreaEventSchedule stored = newSchedule(area, at(2, 21, 0), at(2, 23, 0), CREATE_REASON, "Hội thảo / seminar");

        areaService.activateScheduleInTx(stored.getId(), OffsetDateTime.now());

        AreaEventSchedule after = schedule(stored.getId());
        assertEquals(AreaEventScheduleStatus.FAILED, after.getStatus());
        assertTrue(after.getFailReason().contains("khung ban đêm 22:00–06:00"), after.getFailReason());
        assertEquals(1, auditsWithAction(area, "FAIL").size());
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).isEmpty());
        assertEquals(false, Boolean.TRUE.equals(reload(area).getOpenToMembers()));
        assertEquals(1, notificationsOf(fm, NotificationType.EVENT_MODE_SCHEDULE_FAILED).stream()
                .filter(n -> area.getId().equals(n.getReferenceId())).count());
    }

    // ------------------------------------------------------------------ EV-N8: ADMIN đổi giờ ban đêm

    @Test
    @DisplayName("f) START 22:00 -> 21:00: 18:00–21:30 cắt về 21:00; 20:50–21:30 huỷ (còn 10' < 15'); 21:10–21:40 huỷ (bắt đầu trong ban đêm); 1 audit SYSTEM mỗi lịch; FM + GUARD nhận thông báo; API trả danh sách")
    void f_startEarlier_trimsAndCancelsSchedules() {
        nightWindow("22:00", "06:00");
        Area areaB = nightArea(AreaLevel.CONFIDENTIAL);
        Area areaC = nightArea(AreaLevel.CONFIDENTIAL);
        EventScheduleResponse trim = create(area, at(2, 18, 0), at(2, 21, 30));
        EventScheduleResponse tooShort = create(areaB, at(2, 20, 50), at(2, 21, 30));
        EventScheduleResponse startsInNight = create(areaC, at(2, 21, 10), at(2, 21, 40));
        long guardBefore = notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).size();

        SystemConfigResponse response = systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), "21:00", admin.getEmail());

        AreaEventSchedule trimmed = schedule(trim.id());
        assertEquals(AreaEventScheduleStatus.SCHEDULED, trimmed.getStatus());
        assertEquals(at(2, 21, 0).toInstant(), trimmed.getEndAt().toInstant());
        assertEquals(at(2, 18, 0).toInstant(), trimmed.getStartAt().toInstant());
        for (UUID id : List.of(tooShort.id(), startsInNight.id())) {
            AreaEventSchedule cancelled = schedule(id);
            assertEquals(AreaEventScheduleStatus.CANCELLED, cancelled.getStatus(), id.toString());
            assertNull(cancelled.getCancelledBy(), "huỷ bởi hệ thống");
            assertEquals(AreaService.AFTER_HOURS_CHANGE_REASON, cancelled.getCancelReasonLabel());
        }

        List<AuditLog> trimAudits = afterHoursAudits(trim.id().toString());
        assertEquals(1, trimAudits.size(), trimAudits.toString());
        assertEquals("SYSTEM", trimAudits.get(0).getActorType());
        assertEquals("UPDATE", trimAudits.get(0).getAction().name());
        assertEquals("CANCEL", afterHoursAudits(tooShort.id().toString()).get(0).getAction().name());
        assertEquals(1, afterHoursAudits(startsInNight.id().toString()).size());

        assertEquals(List.of(EventNightAdjustment.OUTCOME_TRIMMED), ofArea(response, area).stream().map(EventNightAdjustment::outcome).toList());
        assertEquals(at(2, 21, 0).toInstant(), ofArea(response, area).get(0).newEndAt().toInstant());
        assertEquals(List.of(EventNightAdjustment.OUTCOME_CANCELLED), ofArea(response, areaB).stream().map(EventNightAdjustment::outcome).toList());
        assertEquals(List.of(EventNightAdjustment.OUTCOME_CANCELLED), ofArea(response, areaC).stream().map(EventNightAdjustment::outcome).toList());

        // Thông báo: FM tạo lịch + FM khác đang hoạt động + GUARD; nêu khu, giờ mới, lý do
        Notification fmTrim = notificationsOf(fm, NotificationType.EVENT_MODE_SCHEDULED).stream()
                .filter(n -> area.getId().equals(n.getReferenceId())).findFirst().orElseThrow();
        assertTrue(fmTrim.getMessage().contains(area.getName()) && fmTrim.getMessage().contains("21:00")
                && fmTrim.getMessage().contains(AreaService.AFTER_HOURS_CHANGE_REASON), fmTrim.getMessage());
        assertTrue(notificationsOf(fm2, NotificationType.EVENT_MODE_SCHEDULED).stream().anyMatch(n -> areaB.getId().equals(n.getReferenceId())));
        long guardAfter = notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).size();
        assertTrue(guardAfter - guardBefore >= 3, "GUARD nhận ít nhất 3 thông báo (3 lịch của test): " + (guardAfter - guardBefore));
    }

    @Test
    @DisplayName("f) Sự kiện đang mở tới 21:30, now = 20:00, ban đêm mới 21:00 -> cắt về 21:00 (phiên, khu, lịch nguồn) + audit EXTEND_EVENT_MODE SYSTEM; now = 20:50 -> tắt ngay, lịch nguồn ENDED_EARLY")
    void f_openEventTrimmedOrStopped() {
        nightWindow("21:00", "06:00");
        Area areaB = nightArea(AreaLevel.CONFIDENTIAL);
        AreaEventSchedule src = startedSchedule(area, at(2, 19, 0), at(2, 21, 30));

        // Gọi đúng hàm hook dùng (SystemConfigService.update -> applyAfterHoursChange) với now điều khiển được
        List<EventNightAdjustment> trimmed = areaService.applyAfterHoursChange(at(2, 20, 0)).stream()
                .filter(a -> area.getId().equals(a.areaId())).toList();
        assertEquals(1, trimmed.size());
        assertEquals(EventNightAdjustment.OUTCOME_TRIMMED, trimmed.get(0).outcome());
        assertEquals(at(2, 21, 0).toInstant(), reload(area).getOpenUntil().toInstant());
        AreaEventSession session = sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).orElseThrow();
        assertEquals(at(2, 21, 0).toInstant(), session.getPlannedEnd().toInstant());
        assertEquals(at(2, 21, 0).toInstant(), schedule(src.getId()).getEndAt().toInstant());
        assertEquals(1, afterHoursAudits(area.getId().toString()).stream().filter(a -> "EXTEND_EVENT_MODE".equals(a.getAction().name())).count());

        // Khu B dựng SAU lần gọi đầu (không thì lần gọi lúc 20:00 đã cắt nó về 21:00)
        AreaEventSchedule srcB = startedSchedule(areaB, at(2, 20, 0), at(2, 21, 30));
        List<EventNightAdjustment> stopped = areaService.applyAfterHoursChange(at(2, 20, 50)).stream()
                .filter(a -> areaB.getId().equals(a.areaId())).toList();
        assertEquals(List.of(EventNightAdjustment.OUTCOME_STOPPED), stopped.stream().map(EventNightAdjustment::outcome).toList());
        assertEquals(false, Boolean.TRUE.equals(reload(areaB).getOpenToMembers()));
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(areaB.getId()).isEmpty());
        assertEquals(AreaEventScheduleStatus.ENDED_EARLY, schedule(srcB.getId()).getStatus());
        assertEquals(1, afterHoursAudits(areaB.getId().toString()).stream().filter(a -> "DISABLE_EVENT_MODE".equals(a.getAction().name())).count());
        assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).stream().anyMatch(n -> areaB.getId().equals(n.getReferenceId())));
    }

    /** Lịch đã STARTED + phiên đang mở + cờ khu (dựng như job kích hoạt đã chạy lúc start). */
    private AreaEventSchedule startedSchedule(Area target, OffsetDateTime start, OffsetDateTime end) {
        AreaEventSchedule s = newSchedule(target, start, end, CREATE_REASON, "Hội thảo / seminar");
        AreaEventSession session = openEvent(target, start, end);
        session.setScheduleId(s.getId());
        sessionRepository.save(session);
        s.setStatus(AreaEventScheduleStatus.STARTED);
        s.setSession(session);
        return eventScheduleRepository.save(s);
    }

    @Test
    @DisplayName("f) Đổi END 06:00 -> 05:30 (ban đêm ngắn lại, không lịch nào chạm) -> danh sách rỗng, GUARD không nhận thêm thông báo")
    void f_nothingAffected_noNotification() {
        nightWindow("22:00", "06:00");
        create(area, at(2, 9, 0), at(2, 11, 0));
        long guardBefore = notificationsOf(guard).size();

        SystemConfigResponse response = systemConfigService.update(ConfigKey.AI_AFTER_HOUR_END.getKey(), "05:30", admin.getEmail());

        assertTrue(response.eventAdjustments().isEmpty(), response.eventAdjustments().toString());
        assertEquals(guardBefore, notificationsOf(guard).size());
    }

    @Test
    @DisplayName("h) Đổi cấu hình khác (EVENT_MODE_MIN_MINUTES) -> không đụng lịch, kể cả lịch sẽ chạm ban đêm nếu khung bật; eventAdjustments = null")
    void h_otherConfigChange_doesNotTouchSchedules() {
        // Khung đang tắt (Step5bTestSupport): lịch 21:30–23:00 hợp lệ lúc tạo
        EventScheduleResponse late = create(area, at(2, 21, 30), at(2, 23, 0));

        SystemConfigResponse response = systemConfigService.update(ConfigKey.EVENT_MODE_MIN_MINUTES.name(), "60", admin.getEmail());

        assertNull(response.eventAdjustments());
        AreaEventSchedule after = schedule(late.id());
        assertEquals(AreaEventScheduleStatus.SCHEDULED, after.getStatus());
        assertEquals(at(2, 23, 0).toInstant(), after.getEndAt().toInstant());
        assertTrue(afterHoursAudits(late.id().toString()).isEmpty());
    }
}
