package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.EventModeAction;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.exception.AreaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Khôi phục các assert thông báo của chế độ sự kiện đã mất khi commit 7f3cd69 (04/10/2026) xoá
 * Step5aSupplement2Test. Nguồn: Step5aSupplement2Test tại 7f3cd69^ — T19, T20, T20b, T21.
 *
 * Viết lại theo fixture hiện tại (Step5bTestSupport, không geometry). Khác bản gốc:
 * - Không gọi notificationRepository.deleteAll(): mọi assert lọc theo người nhận tạo riêng cho từng test
 *   và theo khu vực của test (referenceId / tên khu vực), nên không xoá dữ liệu của test khác.
 * - Config và khu vực đang mở được khôi phục ở @AfterEach, kể cả khi test fail giữa chừng.
 */
class NotificationRulesRestoredIntegrationTest extends Step5bTestSupport {

    private Area internalArea;
    private Area contactArea;

    @BeforeEach
    void setUpAreas() {
        internalArea = newArea(AreaLevel.CONFIDENTIAL);
        contactArea = newArea(AreaLevel.CONFIDENTIAL);
    }

    @AfterEach
    void restoreState() {
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "30", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", admin.getEmail());
        // Đóng sự kiện của test: khu vực còn mở sẽ bị tính là "vi phạm" khi test khác hạ ngân sách
        OffsetDateTime now = OffsetDateTime.now();
        for (Area a : List.of(internalArea, contactArea)) {
            Area fresh = reload(a);
            fresh.setOpenToMembers(false);
            fresh.setOpenUntil(null);
            areaRepository.save(fresh);
            sessionRepository.findAll().stream()
                    .filter(s -> s.getArea().getId().equals(a.getId()) && s.getActualEnd() == null)
                    .forEach(s -> {
                        s.setActualEnd(now);
                        sessionRepository.save(s);
                    });
        }
    }

    private AreaEventModeUpdateRequest eventReq(Area area, EventModeAction action, OffsetDateTime openUntil,
                                                String reasonCode, String note) {
        return AreaEventModeUpdateRequest.builder()
                .action(action)
                .openUntil(openUntil)
                .reasonCode(reasonCode)
                .note(note)
                .version(areaService.getAreaById(area.getId()).version())
                .build();
    }

    /** Thông báo loại type của user, gắn với khu vực area; mới nhất đứng đầu. */
    private List<Notification> notificationsFor(User user, NotificationType type, Area area) {
        return notificationsOf(user, type).stream()
                .filter(n -> area.getId().equals(n.getReferenceId()))
                .toList();
    }

    private List<Notification> notificationsForArea(NotificationType type, Area area) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getType() == type && area.getId().equals(n.getReferenceId()))
                .toList();
    }

    /**
     * BR-EV-31: bật / điều chỉnh / tắt thành công -> thông báo GUARD; bị từ chối / no-op (BR-EV-11) -> không thông báo.
     * Nguồn: Step5aSupplement2Test#testT19_GuardNotificationOnEventModeChanged.
     */
    @Test
    @DisplayName("BR-EV-31: BẬT / ĐIỀU CHỈNH / TẮT thành công -> GUARD đang hoạt động nhận 1 EVENT_MODE_CHANGED mỗi lần; no-op / bị từ chối -> 0")
    void brEv31_guardNotifiedOnEventModeChanged() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. BẬT thành công
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.ENABLE, now.plusHours(2), "SEMINAR", "Bat su kien kiem tra thong bao guard"),
                fm.getEmail());
        List<Notification> afterEnable = notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea);
        assertEquals(1, afterEnable.size());
        assertTrue(afterEnable.get(0).getMessage().contains("Bật chế độ sự kiện"), afterEnable.get(0).getMessage());

        // 2. ĐIỀU CHỈNH no-op (lệch < 1 giây) -> không thông báo mới
        OffsetDateTime sameEnd = reload(internalArea).getOpenUntil();
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.ADJUST, sameEnd, "EVENT_PROLONGED", "Dieu chinh no op khong doi gio"),
                fm.getEmail());
        assertEquals(1, notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea).size(),
                "No-op không được gửi thông báo mới");

        // 3. ĐIỀU CHỈNH thành công
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.ADJUST, now.plusHours(4), "EVENT_PROLONGED", "Dieu chinh gio ket thuc hop le"),
                fm.getEmail());
        List<Notification> afterAdjust = notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea);
        assertEquals(2, afterAdjust.size());
        assertTrue(afterAdjust.get(0).getMessage().contains("Điều chỉnh giờ kết thúc"), afterAdjust.get(0).getMessage());

        // 4. TẮT thành công
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.DISABLE, null, "ENDED_EARLY", "Tat su kien hop le theo lich"),
                fm.getEmail());
        List<Notification> afterDisable = notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea);
        assertEquals(3, afterDisable.size());
        assertTrue(afterDisable.get(0).getMessage().contains("Tắt chế độ sự kiện"), afterDisable.get(0).getMessage());

        // 5. Bị từ chối (tắt khi đã tắt) -> không thông báo mới
        assertThrows(AreaException.class, () -> areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.DISABLE, null, "ENDED_EARLY", "Thu tat khi da tat"),
                fm.getEmail()));
        assertEquals(3, notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea).size(),
                "Thao tác bị từ chối không sinh thông báo");
    }

    /**
     * BR-EV-32: nhắc FM đã bật trước EVENT_MODE_EXPIRY_REMINDER_MINUTES phút; chỉ nhắc 1 lần; REMINDER = 0 tắt nhắc;
     * người bật không còn hoạt động -> mọi FM đang hoạt động nhận.
     * Nguồn: Step5aSupplement2Test#testT20_ExpiryReminderJob.
     */
    @Test
    @DisplayName("BR-EV-32: phiên còn 20 phút, REMINDER=30 -> started_by nhận 1 EVENT_MODE_EXPIRING; chạy lần 2 vẫn 1; REMINDER=0 -> không nhắc; started_by bị khoá -> FM khác nhận")
    void brEv32_expiryReminderJob() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Phiên còn 20 phút, REMINDER = 30 (đặt ở @AfterEach của lần trước / mặc định)
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "30", admin.getEmail());
        AreaEventSession session = openEvent(internalArea, now.minusHours(2), now.plusMinutes(20));

        areaService.scanAndSendEventModeExpiryReminders();

        assertEquals(1, notificationsFor(fm, NotificationType.EVENT_MODE_EXPIRING, internalArea).size());
        assertNotNull(sessionRepository.findById(session.getId()).orElseThrow().getExpiryRemindedAt());

        // Chạy lần 2 -> không tạo thêm thông báo
        areaService.scanAndSendEventModeExpiryReminders();
        assertEquals(1, notificationsFor(fm, NotificationType.EVENT_MODE_EXPIRING, internalArea).size());

        // 2. REMINDER = 0 -> không nhắc
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "0", admin.getEmail());
        AreaEventSession session2 = openEvent(contactArea, now.minusHours(2), now.plusMinutes(20));

        areaService.scanAndSendEventModeExpiryReminders();
        assertNull(sessionRepository.findById(session2.getId()).orElseThrow().getExpiryRemindedAt());

        // 3. started_by bị vô hiệu hoá -> mọi FM đang hoạt động nhận.
        // Khoá fm TRƯỚC khi bật lại REMINDER: job theo lịch (cron nhắc hạn không tắt trong profile test) không kịp
        // gửi cho fm giữa hai bước.
        fm.setIsActive(false);
        userRepository.save(fm);
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "30", admin.getEmail());

        areaService.scanAndSendEventModeExpiryReminders();

        assertFalse(notificationsFor(fm2, NotificationType.EVENT_MODE_EXPIRING, contactArea).isEmpty(),
                "FM đang hoạt động khác phải nhận nhắc hạn khi người bật đã bị khoá");
    }

    /**
     * BR-EV-32: job nhắc hạn bỏ qua phiên của khu vực không đang mở sự kiện.
     * Nguồn: Step5aSupplement2Test#testT20b_ReminderSkipsExpiredOrInactive.
     */
    @Test
    @DisplayName("BR-EV-32: job nhắc hạn bỏ qua phiên có cờ khu vực đã tắt -> expiry_reminded_at vẫn NULL, 0 thông báo")
    void brEv32_reminderSkipsInactiveArea() {
        OffsetDateTime now = OffsetDateTime.now();
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "30", admin.getEmail());

        // Phiên còn 20 phút nhưng cờ khu vực open_to_members = false
        AreaEventSession session = sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusHours(2))
                .plannedEnd(now.plusMinutes(20))
                .actualEnd(null)
                .startedBy(fm)
                .expiryRemindedAt(null)
                .build());
        Area fresh = reload(internalArea);
        fresh.setOpenToMembers(false);
        fresh.setOpenUntil(null);
        areaRepository.save(fresh);

        areaService.scanAndSendEventModeExpiryReminders();

        assertNull(sessionRepository.findById(session.getId()).orElseThrow().getExpiryRemindedAt(),
                "Job không được nhắc khi khu vực không đang mở");
        assertTrue(notificationsForArea(NotificationType.EVENT_MODE_EXPIRING, internalArea).isEmpty(),
                "0 thông báo khi khu vực không đang mở sự kiện");
    }

    /**
     * BR-EV-29: hạ BUDGET_HOURS khi có khu vực đang mở vượt ngân sách mới -> mọi FM đang hoạt động nhận 1
     * EVENT_MODE_LIMIT_CHANGED kèm danh sách; hạ khi không vi phạm -> không gửi.
     * Nguồn: Step5aSupplement2Test#testT21_BudgetReductionViolatingLimitNotification.
     */
    @Test
    @DisplayName("BR-EV-29: hạ BUDGET_HOURS khi khu vực đang mở vượt ngân sách mới -> FM nhận 1 EVENT_MODE_LIMIT_CHANGED nêu tên khu vực; không vi phạm -> 0")
    void brEv29_budgetReductionNotifiesFm() {
        OffsetDateTime now = OffsetDateTime.now();

        // Như bản gốc: tắt các khu vực đang mở khác trên DB test để bước "không vi phạm" đo đúng
        for (Area a : areaRepository.findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(now)) {
            a.setOpenToMembers(false);
            a.setOpenUntil(null);
            areaRepository.save(a);
        }

        // Chuỗi phiên 40h trong 7 ngày cho internalArea
        sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusDays(4))
                .plannedEnd(now.minusDays(4).plusHours(20))
                .actualEnd(now.minusDays(4).plusHours(20))
                .startedBy(fm)
                .endedBy(fm)
                .build());
        sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusDays(2))
                .plannedEnd(now.minusDays(2).plusHours(20))
                .actualEnd(now.minusDays(2).plusHours(20))
                .startedBy(fm)
                .endedBy(fm)
                .build());

        // Bật thêm 4h -> tổng 44h <= 48h
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.ENABLE, now.plusHours(4), "SEMINAR", "Bat them 4 gio tong 44 gio"),
                fm.getEmail());

        assertEquals(0, notificationsOf(fm, NotificationType.EVENT_MODE_LIMIT_CHANGED).size());

        // Hạ BUDGET_HOURS còn 30 (44h > 30h) -> FM nhận 1 thông báo nêu tên khu vực
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "30", admin.getEmail());

        List<Notification> limitNotifs = notificationsOf(fm, NotificationType.EVENT_MODE_LIMIT_CHANGED);
        assertEquals(1, limitNotifs.size(), "Mỗi FM đang hoạt động nhận đúng 1 thông báo");
        assertTrue(limitNotifs.get(0).getMessage().contains(internalArea.getName()), limitNotifs.get(0).getMessage());
        assertEquals(1, notificationsOf(fm2, NotificationType.EVENT_MODE_LIMIT_CHANGED).size(), "FM khác cũng nhận");

        // Khôi phục BUDGET_HOURS = 48 rồi tắt sự kiện
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", admin.getEmail());
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.DISABLE, null, "ENDED_EARLY", "Tat de khong con vi pham"),
                fm.getEmail());

        // Tiền điều kiện của bước "không vi phạm": DB test dùng chung không còn khu vực nào vi phạm ngân sách 30h.
        // Nếu còn (vd lịch sự kiện SCHEDULED của test khác, BR-ES-07), bước dưới không đo được -> fail kèm tên khu vực.
        List<String> stillViolating = areaService.findAreasViolatingNewEventLimits(
                areaService.getEventModeMaxHours(), areaService.getEventModeWindowDays(), 30)
                .stream().map(Area::getName).toList();
        assertTrue(stillViolating.isEmpty(), "Dữ liệu DB test còn khu vực vi phạm ngân sách 30h: " + stillViolating);

        long before = notificationsOf(fm, NotificationType.EVENT_MODE_LIMIT_CHANGED).size();
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "30", admin.getEmail());
        assertEquals(before, notificationsOf(fm, NotificationType.EVENT_MODE_LIMIT_CHANGED).size(),
                "Hạ ngân sách khi không có khu vực vi phạm -> không gửi thông báo");
    }
}
