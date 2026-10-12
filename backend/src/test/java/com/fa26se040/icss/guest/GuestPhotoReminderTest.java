package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.service.GuestVisitJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-GV-39: lượt APPROVED còn khách chưa PHOTO_READY, now ∈ [start − GUEST_PHOTO_REMINDER_MINUTES_BEFORE, start)
 * -> ADMIN nhận GUEST_PHOTO_REQUIRED đúng 1 lần (chống trùng theo thông báo tạo từ đầu cửa sổ, không thêm cột).
 */
class GuestPhotoReminderTest extends GuestTestSupport {

    private static final String DEFAULT_MINUTES = ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE.getDefaultValue();

    @Autowired private GuestVisitJobService jobService;

    @AfterEach
    void restoreReminderConfig() {
        // DB test dùng chung: trả khoá về mặc định
        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, DEFAULT_MINUTES);
    }

    private List<Notification> reminders(GuestVisit v) {
        return notificationsOf(admin, NotificationType.GUEST_PHOTO_REQUIRED).stream()
                .filter(n -> v.getId().equals(n.getReferenceId())).toList();
    }

    @Test
    @DisplayName("GV-39: trong cửa sổ + còn khách chưa có ảnh -> ADMIN nhận 1 lần; chạy lại không gửi thêm; nội dung không có tên khách")
    void inWindowMissingPhoto_remindedOnce() {
        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "60");
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusMinutes(30), now.plusHours(2), List.of(internalArea),
                "Khách Nhắc Một", "Khách Nhắc Hai");
        withPhoto(guestsOf(v.getId()).get(0), now.plusDays(1));

        jobService.remindMissingPhotos(now);
        List<Notification> sent = reminders(v);
        assertEquals(1, sent.size(), "ADMIN nhận đúng 1 nhắc");
        String msg = sent.get(0).getMessage();
        assertTrue(msg.contains("1/2 khách chưa có ảnh"), msg);
        assertFalse(msg.contains("Khách Nhắc Một") || msg.contains("Khách Nhắc Hai"), "không ghi tên khách: " + msg);
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_PHOTO_REQUIRED).stream()
                .noneMatch(n -> v.getId().equals(n.getReferenceId())), "host không nhận GUEST_PHOTO_REQUIRED");

        jobService.remindMissingPhotos(now.plusMinutes(1));
        jobService.remindMissingPhotos(now.plusMinutes(10));
        assertEquals(1, reminders(v).size(), "chạy lại không gửi thêm");
    }

    @Test
    @DisplayName("GV-39: mọi khách đã PHOTO_READY -> không nhắc")
    void allReady_notReminded() {
        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "60");
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusMinutes(30), now.plusHours(2), List.of(internalArea), "Khách Đủ Ảnh");
        withPhoto(guestsOf(v.getId()).get(0), now.plusDays(1));

        jobService.remindMissingPhotos(now);
        assertEquals(0, reminders(v).size());
    }

    @Test
    @DisplayName("GV-39: ngoài cửa sổ (còn quá xa giờ bắt đầu, hoặc đã bắt đầu) -> không nhắc")
    void outsideWindow_notReminded() {
        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "60");
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit far = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusMinutes(90), now.plusHours(3), List.of(internalArea), "Khách Còn Xa");
        GuestVisit started = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusMinutes(5), now.plusHours(1), List.of(internalArea), "Khách Đã Vào");

        jobService.remindMissingPhotos(now);
        assertEquals(0, reminders(far).size(), "start − 60' chưa tới");
        assertEquals(0, reminders(started).size(), "đã quá giờ bắt đầu");
    }

    @Test
    @DisplayName("GV-39: lượt đã huỷ / bị thu hồi -> không nhắc")
    void cancelledOrRevoked_notReminded() {
        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "60");
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit cancelled = newVisit(hostL2, GuestVisitStatus.CANCELLED, now.plusMinutes(30), now.plusHours(2), List.of(internalArea), "Khách Lượt Huỷ");
        GuestVisit revoked = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusMinutes(30), now.plusHours(2), List.of(internalArea), "Khách Lượt Thu Hồi");
        transactionTemplate.executeWithoutResult(tx -> {
            GuestVisit x = guestVisitRepository.findById(revoked.getId()).orElseThrow();
            x.setStatus(GuestVisitStatus.REVOKED);
            x.setRevokedBy(fm);
            x.setRevokedAt(OffsetDateTime.now());
            x.setRevokeReason("Thu hồi để thử nhắc gắn ảnh");
            guestVisitRepository.save(x);
        });

        jobService.remindMissingPhotos(now);
        assertEquals(0, reminders(cancelled).size());
        assertEquals(0, reminders(revoked).size());
    }

    @Test
    @DisplayName("GV-39: đổi GUEST_PHOTO_REMINDER_MINUTES_BEFORE -> cửa sổ đổi theo ngay (5 phút: chưa nhắc; 60 phút: nhắc)")
    void configChange_movesWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusMinutes(30), now.plusHours(2), List.of(internalArea), "Khách Theo Cấu Hình");

        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "5");
        jobService.remindMissingPhotos(now);
        assertEquals(0, reminders(v).size(), "cửa sổ 5 phút: còn 30 phút nên chưa nhắc");

        setConfig(ConfigKey.GUEST_PHOTO_REMINDER_MINUTES_BEFORE, "60");
        jobService.remindMissingPhotos(now);
        assertEquals(1, reminders(v).size(), "cửa sổ 60 phút: đã vào cửa sổ nên nhắc");
    }
}
