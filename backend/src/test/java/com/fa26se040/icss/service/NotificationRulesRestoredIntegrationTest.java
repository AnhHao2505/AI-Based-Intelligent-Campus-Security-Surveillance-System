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
        // Đóng sự kiện của test: không để phiên mở sót lại trên DB test dùng chung
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
     * BR-EV-31: bật (qua lịch, X-01a) / tắt thành công -> thông báo GUARD; bị từ chối -> không thông báo.
     * Nguồn: Step5aSupplement2Test#testT19_GuardNotificationOnEventModeChanged.
     */
    @Test
    @DisplayName("BR-EV-31: BẬT (lịch được job kích hoạt) / TẮT thành công -> GUARD đang hoạt động nhận 1 EVENT_MODE_CHANGED mỗi lần; bị từ chối -> 0")
    void brEv31_guardNotifiedOnEventModeChanged() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. BẬT thành công (X-01a: chỉ mở qua lịch + job kích hoạt)
        openEventViaSchedule(internalArea, now.minusMinutes(1), now.plusHours(2));
        List<Notification> afterEnable = notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea);
        assertEquals(1, afterEnable.size());
        assertTrue(afterEnable.get(0).getMessage().contains("Bật chế độ sự kiện"), afterEnable.get(0).getMessage());

        // 2. TẮT thành công
        areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.DISABLE, null, "ENDED_EARLY", "Tat su kien hop le theo lich"),
                fm.getEmail());
        List<Notification> afterDisable = notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea);
        assertEquals(2, afterDisable.size());
        assertTrue(afterDisable.get(0).getMessage().contains("Tắt chế độ sự kiện"), afterDisable.get(0).getMessage());

        // 3. Bị từ chối (tắt khi đã tắt) -> không thông báo mới
        assertThrows(AreaException.class, () -> areaService.updateEventMode(internalArea.getId(),
                eventReq(internalArea, EventModeAction.DISABLE, null, "ENDED_EARLY", "Thu tat khi da tat"),
                fm.getEmail()));
        assertEquals(2, notificationsFor(guard, NotificationType.EVENT_MODE_CHANGED, internalArea).size(),
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

    // V115 (EV-N1): BR-EV-29 (hạ BUDGET_HOURS -> FM nhận EVENT_MODE_LIMIT_CHANGED) đã bỏ cùng ngân sách giờ; test brEv29 xoá.
}
