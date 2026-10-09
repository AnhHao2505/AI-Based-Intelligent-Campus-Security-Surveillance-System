package com.fa26se040.icss.guest;

import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestAccessDecisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5 — G-C: checkGuestEntry (BR-GV-20, 21). Chỉ service, chưa nối vào luồng AI.
 */
public class GuestEntryDecisionTest extends GuestTestSupport {

    @Autowired
    private GuestAccessDecisionService decisionService;

    private OffsetDateTime now;

    private Guest activeGuest(Area area) {
        now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(1), List.of(area), "Khách đang trong lượt");
        // BR-GV-38: chỉ khách PHOTO_READY mới được vào -> khách "đang trong lượt" mặc định đã đăng ký khuôn mặt
        return withPhoto(guestsOf(v.getId()).get(0), now.plusDays(1));
    }

    private void assertDenied(GuestAccessDecision d, GuestEntryDenyReason reason) {
        assertFalse(d.allowed(), "phải từ chối: " + d);
        assertEquals(reason, d.reason());
        assertEquals("NONE", d.source());
    }

    @Test
    @DisplayName("GV-20: lượt APPROVED, trong khung, khu vực thuộc lượt, host còn quyền -> cho vào, nguồn GUEST_VISIT")
    void allowed() {
        Guest g = activeGuest(internalArea);
        GuestAccessDecision d = decisionService.checkGuestEntry(g.getId(), internalArea.getId(), now);
        assertTrue(d.allowed(), d.toString());
        assertEquals("GUEST_VISIT", d.source());
        assertNull(d.reason());
        assertEquals(g.getVisit().getId(), d.visitId());
    }

    @Test
    @DisplayName("GV-20 VISIT_NOT_ACTIVE: lượt PENDING / CANCELLED / khách không tồn tại")
    void visitNotActive() {
        OffsetDateTime t = OffsetDateTime.now();
        GuestVisit pending = newVisit(hostL2, GuestVisitStatus.PENDING, t.minusHours(1), t.plusHours(1), List.of(internalArea), "Khách chờ duyệt");
        assertDenied(decisionService.checkGuestEntry(guestsOf(pending.getId()).get(0).getId(), internalArea.getId(), t),
                GuestEntryDenyReason.VISIT_NOT_ACTIVE);
        GuestVisit cancelled = transactionTemplate.execute(tx -> {
            GuestVisit x = newVisit(hostL2, GuestVisitStatus.APPROVED, t.minusHours(1), t.plusHours(1), List.of(internalArea), "Khách đã huỷ");
            GuestVisit y = guestVisitRepository.findById(x.getId()).orElseThrow();
            y.setStatus(GuestVisitStatus.CANCELLED);
            y.setCancelledAt(t);
            return guestVisitRepository.save(y);
        });
        assertDenied(decisionService.checkGuestEntry(guestsOf(cancelled.getId()).get(0).getId(), internalArea.getId(), t),
                GuestEntryDenyReason.VISIT_NOT_ACTIVE);
        assertDenied(decisionService.checkGuestEntry(UUID.randomUUID(), internalArea.getId(), t), GuestEntryDenyReason.VISIT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("GV-20 OUTSIDE_WINDOW: trước start 1 giây; đúng end (end loại trừ)")
    void outsideWindow() {
        Guest g = activeGuest(internalArea);
        GuestVisit v = visit(g.getVisit().getId());
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), v.getStartTime().minusSeconds(1)), GuestEntryDenyReason.OUTSIDE_WINDOW);
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), v.getEndTime()), GuestEntryDenyReason.OUTSIDE_WINDOW);
        assertTrue(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), v.getStartTime()).allowed(), "start tính là trong khung");
    }

    @Test
    @DisplayName("GV-20 AREA_NOT_IN_VISIT: khu vực không thuộc lượt")
    void areaNotInVisit() {
        Guest g = activeGuest(internalArea);
        Area other = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        assertDenied(decisionService.checkGuestEntry(g.getId(), other.getId(), now), GuestEntryDenyReason.AREA_NOT_IN_VISIT);
    }

    @Test
    @DisplayName("GV-20 AREA_INACTIVE: khu vực của lượt ngừng hoạt động sau khi duyệt")
    void areaInactive() {
        Guest g = activeGuest(internalArea);
        Area a = reload(internalArea);
        a.setIsActive(false);
        areaRepository.save(a);
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), now), GuestEntryDenyReason.AREA_INACTIVE);
    }

    @Test
    @DisplayName("GV-20 AREA_TYPE_NOT_ALLOWED: khu vực đổi sang HIGHLY (hoặc PUBLIC) sau khi duyệt")
    void areaTypeChanged() {
        Guest g = activeGuest(internalArea);
        Area a = reload(internalArea);
        a.setAreaLevel(AreaLevel.HIGHLY_CONFIDENTIAL);
        areaRepository.save(a);
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), now), GuestEntryDenyReason.AREA_TYPE_NOT_ALLOWED);
        a.setAreaLevel(AreaLevel.PUBLIC);
        areaRepository.save(a);
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), now), GuestEntryDenyReason.AREA_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("GV-20 HOST_LOST_ACCESS: host bị hạ cấp dưới GUEST_HOST_MIN_LEVEL / host bị vô hiệu hoá / AP của host bị thu hồi")
    void hostLostAccess() {
        Guest g1 = activeGuest(internalArea);
        User h = userRepository.findById(hostL2.getId()).orElseThrow();
        h.setAccessLevel(1);
        userRepository.save(h);
        assertDenied(decisionService.checkGuestEntry(g1.getId(), internalArea.getId(), now), GuestEntryDenyReason.HOST_LOST_ACCESS);

        OffsetDateTime t = OffsetDateTime.now();
        User host2 = newUser("h2c", Role.NORMAL_USER, 2, true);
        GuestVisit v2 = newVisit(host2, GuestVisitStatus.APPROVED, t.minusHours(1), t.plusHours(1), List.of(internalArea), "Khách host bị khoá");
        host2.setIsActive(false);
        userRepository.save(host2);
        assertDenied(decisionService.checkGuestEntry(guestsOf(v2.getId()).get(0).getId(), internalArea.getId(), t), GuestEntryDenyReason.HOST_LOST_ACCESS);

        User host3 = newUser("h2d", Role.NORMAL_USER, 2, true);
        AreaAssignedPersonnel ap = newAp(contactArea, host3, t.minusDays(1), t.plusDays(1));
        GuestVisit v3 = newVisit(host3, GuestVisitStatus.APPROVED, t.minusHours(1), t.plusHours(1), List.of(contactArea), "Khách host mất AP");
        Guest g3 = withPhoto(guestsOf(v3.getId()).get(0), t.plusDays(1)); // BR-GV-38: cần PHOTO_READY để "còn AP thì cho vào"
        assertTrue(decisionService.checkGuestEntry(g3.getId(), contactArea.getId(), t).allowed(), "còn AP thì cho vào");
        ap.setRevokedAt(t);
        ap.setRevokedBy(fm);
        ap.setRevokeReason("Thu hồi AP để thử HOST_LOST_ACCESS");
        assignedPersonnelRepository.save(ap);
        assertDenied(decisionService.checkGuestEntry(g3.getId(), contactArea.getId(), t), GuestEntryDenyReason.HOST_LOST_ACCESS);
    }

    @Test
    @DisplayName("GV-20 BIOMETRIC_DELETED: sinh trắc của khách đã xoá")
    void biometricDeleted() {
        Guest g = activeGuest(internalArea);
        transactionTemplate.executeWithoutResult(tx -> {
            Guest x = guestRepository.findById(g.getId()).orElseThrow();
            x.setBiometricStatus(GuestBiometricStatus.DELETED);
            x.setBiometricDeletedAt(OffsetDateTime.now());
            x.setPhotoObjectKey(null); // chk_guests_deleted: khách giờ có ảnh (BR-GV-38) -> xoá sinh trắc bỏ luôn khoá ảnh
            guestRepository.save(x);
        });
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), now), GuestEntryDenyReason.BIOMETRIC_DELETED);
    }

    @Test
    @DisplayName("GV-38 BIOMETRIC_NOT_READY: khách NO_PHOTO / PHOTO_ONLY bị từ chối; PHOTO_READY cho vào; DELETED vẫn là BIOMETRIC_DELETED")
    void biometricNotReady() {
        OffsetDateTime t = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, t.minusHours(1), t.plusHours(1), List.of(internalArea), "Khách chưa có ảnh");
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(GuestBiometricStatus.NO_PHOTO, g.getBiometricStatus(), "tiền đề: khách mới tạo chưa có ảnh");
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), t), GuestEntryDenyReason.BIOMETRIC_NOT_READY);

        // PHOTO_ONLY: có ảnh + đồng ý nhưng chưa có embedding (chk_guests_photo_consent cần đủ trường ảnh / đồng ý)
        withPhoto(g, t.plusDays(1));
        setStatus(g, GuestBiometricStatus.PHOTO_ONLY);
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), t), GuestEntryDenyReason.BIOMETRIC_NOT_READY);

        setStatus(g, GuestBiometricStatus.PHOTO_READY);
        GuestAccessDecision ready = decisionService.checkGuestEntry(g.getId(), internalArea.getId(), t);
        assertTrue(ready.allowed(), ready.toString());
        assertEquals("GUEST_VISIT", ready.source());

        transactionTemplate.executeWithoutResult(tx -> {
            Guest x = guestRepository.findById(g.getId()).orElseThrow();
            x.setBiometricStatus(GuestBiometricStatus.DELETED);
            x.setBiometricDeletedAt(OffsetDateTime.now());
            x.setPhotoObjectKey(null);
            guestRepository.save(x);
        });
        assertDenied(decisionService.checkGuestEntry(g.getId(), internalArea.getId(), t), GuestEntryDenyReason.BIOMETRIC_DELETED);
    }

    private void setStatus(Guest g, GuestBiometricStatus status) {
        transactionTemplate.executeWithoutResult(tx -> {
            Guest x = guestRepository.findById(g.getId()).orElseThrow();
            x.setBiometricStatus(status);
            guestRepository.save(x);
        });
    }

    @Test
    @DisplayName("GV-21: khu vực đang mở chế độ sự kiện nhưng khách ngoài lượt / ngoài khung -> vẫn từ chối")
    void eventModeNotApplied() {
        OffsetDateTime t = OffsetDateTime.now();
        Area eventArea = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(eventArea, t.minusMinutes(30), t.plusMinutes(30));
        Guest g = activeGuest(internalArea);
        assertDenied(decisionService.checkGuestEntry(g.getId(), eventArea.getId(), t), GuestEntryDenyReason.AREA_NOT_IN_VISIT);

        GuestVisit future = newVisit(hostL2, GuestVisitStatus.APPROVED, t.plusMinutes(10), t.plusHours(2), List.of(eventArea), "Khách tới sau");
        assertDenied(decisionService.checkGuestEntry(guestsOf(future.getId()).get(0).getId(), eventArea.getId(), t), GuestEntryDenyReason.OUTSIDE_WINDOW);
    }
}
