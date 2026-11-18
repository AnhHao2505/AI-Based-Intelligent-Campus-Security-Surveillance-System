package com.fa26se040.icss.guest;

import com.fa26se040.icss.dto.guest.GuestVisitCancelRequest;
import com.fa26se040.icss.dto.guest.GuestVisitRevokeRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestVisitJobService;
import com.fa26se040.icss.service.GuestVisitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P6 — job lượt khách (BR-GV-12, 25, 27, 29, 30). Gọi thẳng method service với now truyền vào; lịch chạy thật tắt trong profile test.
 */
public class GuestJobTest extends GuestTestSupport {

    @Autowired
    private GuestVisitJobService jobService;

    @Autowired
    private GuestVisitService guestVisitService;

    private void assertSystemAudit(List<AuditLog> logs) {
        assertEquals(1, logs.size(), "đúng 1 audit");
        AuditLog a = logs.get(0);
        assertEquals("SYSTEM", a.getActorType());
        assertEquals(GuestVisitJobService.SOURCE, a.getActorSource());
        String snap = a.getOldValue() + "|" + a.getNewValue();
        assertFalse(snap.contains("visits/") || snap.contains("http") || snap.contains("[0.0") || snap.contains("embedding"),
                "snapshot không có ảnh / URL / embedding: " + snap);
    }

    @Test
    @DisplayName("GV-12/30/33: PENDING khi now ≥ start -> EXPIRED, audit EXPIRE actor SYSTEM, host nhận EXPIRED; PENDING chưa tới giờ giữ nguyên; chạy lại không đổi gì")
    void expirePending() {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit due = newVisit(hostL2, GuestVisitStatus.PENDING, now.minusMinutes(1), now.plusHours(1), List.of(internalArea), "Khách chưa duyệt");
        GuestVisit notYet = newVisit(hostL2, GuestVisitStatus.PENDING, now.plusMinutes(30), now.plusHours(2), List.of(internalArea), "Khách sắp tới");
        jobService.expirePendingVisits(now);
        GuestVisit v = visit(due.getId());
        assertEquals(GuestVisitStatus.EXPIRED, v.getStatus());
        assertNotNull(v.getClosedAt());
        assertEquals(1L, v.getVersion());
        assertSystemAudit(audits(due.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.EXPIRE));
        assertEquals(1, notificationsOf(hostL2, NotificationType.GUEST_VISIT_EXPIRED).stream().filter(n -> due.getId().equals(n.getReferenceId())).count());
        assertEquals(GuestVisitStatus.PENDING, visit(notYet.getId()).getStatus());

        jobService.expirePendingVisits(now);
        assertEquals(1L, visit(due.getId()).getVersion(), "chạy lại không đổi version");
        assertEquals(1, audits(due.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.EXPIRE).size(), "không audit thêm");
        assertEquals(1, notificationsOf(hostL2, NotificationType.GUEST_VISIT_EXPIRED).stream().filter(n -> due.getId().equals(n.getReferenceId())).count());
    }

    @Test
    @DisplayName("GV-25/30: APPROVED khi now ≥ end -> COMPLETED, audit COMPLETE actor SYSTEM, sinh trắc còn giữ tới end + retention; APPROVED chưa hết giữ nguyên; idempotent")
    void completeApproved() {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit done = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(2), now.minusMinutes(1), List.of(internalArea), "Khách xong");
        Guest g = withPhoto(guestsOf(done.getId()).get(0), now.minusMinutes(1).plusHours(24));
        GuestVisit running = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(1), List.of(internalArea), "Khách đang ở");
        resetRefreshCalls();
        jobService.completeApprovedVisits(now);
        assertEquals(List.of(false), refreshCallsInTransaction, "L3: job chuyển COMPLETED -> báo AI 1 lần, sau khi các transaction đã commit");
        assertEquals(GuestVisitStatus.COMPLETED, visit(done.getId()).getStatus());
        assertNotNull(visit(done.getId()).getClosedAt());
        assertSystemAudit(audits(done.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.COMPLETE));
        assertEquals(GuestVisitStatus.APPROVED, visit(running.getId()).getStatus());
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "chưa tới end + retention");
        resetRefreshCalls();
        jobService.completeApprovedVisits(now);
        assertTrue(refreshCallsInTransaction.isEmpty(), "L3: không có lượt nào chuyển COMPLETED -> không báo AI");
        assertEquals(1, audits(done.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.COMPLETE).size());
    }

    @Test
    @DisplayName("GV-27: lượt kết thúc bình thường -> xoá tại end + GUEST_FACE_RETENTION_HOURS (mặc định 72 h, V133): trước mốc 1 phút còn giữ, tới mốc thì xoá (ảnh + embedding), audit SYSTEM; chạy lại không audit thêm")
    void deleteAtRetention() throws Exception {
        int retention = systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS);
        assertEquals(72, retention, "V133: mặc định 72 giờ");
        // tròn giây: mốc so sánh không lệch do DB làm tròn micro giây
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.COMPLETED, now.minusHours(3), now.minusHours(1), List.of(internalArea), "Khách hôm qua");
        OffsetDateTime mark = v.getEndTime().plusHours(retention);
        Guest g = withPhoto(guestsOf(v.getId()).get(0), mark);
        String key = g.getPhotoObjectKey();
        jobService.deleteDueBiometrics(mark.minusMinutes(1));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "end + retention − 1 phút: chưa xoá");
        verify(photoStorage, never()).removeObject(key);

        OffsetDateTime later = mark;
        jobService.deleteDueBiometrics(later);
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.DELETED, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()));
        verify(photoStorage).removeObject(key);
        assertSystemAudit(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC));

        jobService.deleteDueBiometrics(later);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size(), "idempotent");
    }

    @Test
    @DisplayName("GV-27: mốc sớm nhất — giảm retention còn 1h thì xoá ngay; embedding tới expires_at trước end + retention thì cũng xoá")
    void deleteAtEarliestMark() {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v1 = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(4), now.minusHours(2), List.of(internalArea), "Khách retention ngắn");
        Guest g1 = withPhoto(guestsOf(v1.getId()).get(0), now.minusHours(2).plusHours(24));
        setConfig(ConfigKey.GUEST_FACE_RETENTION_HOURS, "1");
        jobService.deleteDueBiometrics(now);
        assertEquals(GuestBiometricStatus.DELETED, guest(g1.getId()).getBiometricStatus(), "end + 1h đã qua");

        setConfig(ConfigKey.GUEST_FACE_RETENTION_HOURS, ConfigKey.GUEST_FACE_RETENTION_HOURS.getDefaultValue());
        GuestVisit v2 = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.minusMinutes(10), List.of(internalArea), "Khách embedding hết hạn");
        Guest g2 = withPhoto(guestsOf(v2.getId()).get(0), now.minusMinutes(1));
        jobService.deleteDueBiometrics(now);
        assertEquals(GuestBiometricStatus.DELETED, guest(g2.getId()).getBiometricStatus(), "expires_at đã tới");
        assertFalse(embeddingRepository.existsById(g2.getId()));
    }

    @Test
    @DisplayName("GV-27: lượt khách chưa từng có quyền (CANCELLED trước giờ bắt đầu / EXPIRED / REJECTED) còn sót ảnh -> job xoá ngay")
    void deleteForClosedVisits() {
        OffsetDateTime now = OffsetDateTime.now();
        for (GuestVisitStatus st : List.of(GuestVisitStatus.CANCELLED, GuestVisitStatus.EXPIRED, GuestVisitStatus.REJECTED)) {
            GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.plusHours(1), now.plusHours(3), List.of(internalArea), "Khách " + st);
            Guest g = withPhoto(guestsOf(v.getId()).get(0), now.plusHours(75));
            transactionTemplate.executeWithoutResult(tx -> {
                GuestVisit x = guestVisitRepository.findById(v.getId()).orElseThrow();
                x.setStatus(st);
                x.setCancelledAt(now);
                x.setReviewReason(REASON);
                x.setClosedAt(now);
                guestVisitRepository.save(x);
            });
            jobService.deleteDueBiometrics(now);
            assertEquals(GuestBiometricStatus.DELETED, guest(g.getId()).getBiometricStatus(), st.name());
        }
    }

    @Test
    @DisplayName("GV-13/27: lượt bị thu hồi -> job chưa xoá tại revokedAt + retention − 1 phút; tại revokedAt + retention thì xoá ảnh + embedding, audit DELETE_BIOMETRIC actor SYSTEM, ảnh xoá khỏi kho sau commit")
    void deleteRevokedAtRetention() throws Exception {
        int retention = systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS);
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(2), List.of(internalArea), "Khách bị thu hồi");
        // expires_at như lúc gắn ảnh: end + retention, muộn hơn revokedAt + retention -> chỉ nhánh REVOKED xoá được
        Guest g = withPhoto(guestsOf(v.getId()).get(0), v.getEndTime().plusHours(retention));
        String key = g.getPhotoObjectKey();
        guestVisitService.revoke(v.getId(), new GuestVisitRevokeRequest(0L, REASON), fm.getEmail());
        OffsetDateTime revokedAt = visit(v.getId()).getRevokedAt();
        assertNotNull(revokedAt);
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "thu hồi không xoá ngay");

        OffsetDateTime mark = revokedAt.plusHours(retention);
        jobService.deleteDueBiometrics(mark.minusMinutes(1));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "revokedAt + retention − 1 phút: chưa xoá");
        assertTrue(embeddingRepository.existsById(g.getId()));
        verify(photoStorage, never()).removeObject(key);
        assertTrue(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).isEmpty());

        jobService.deleteDueBiometrics(mark);
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.DELETED, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()));
        verify(photoStorage).removeObject(key);
        assertNull(after.getPendingDeleteObjectKey(), "xoá ảnh sau commit thành công -> bỏ đánh dấu");
        List<AuditLog> logs = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC);
        assertSystemAudit(logs);
        assertTrue(logs.get(0).getNewValue().replace(" ", "").contains("\"photoDeletionScheduled\":true"),
                "snapshot ghi photoDeletionScheduled = true (xoá ảnh sau commit)");

        jobService.deleteDueBiometrics(mark);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size(), "idempotent");
    }

    @Test
    @DisplayName("GV-13/27: GUEST_FACE_RETENTION_HOURS = 0 -> thu hồi không xoá ngay, job chạy kế tiếp thì xoá")
    void retentionZero_revokedDeletedOnNextRun() throws Exception {
        setConfig(ConfigKey.GUEST_FACE_RETENTION_HOURS, "0");
        try {
            OffsetDateTime now = OffsetDateTime.now();
            GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(2), List.of(internalArea), "Khách retention 0");
            Guest g = withPhoto(guestsOf(v.getId()).get(0), v.getEndTime());
            String key = g.getPhotoObjectKey();
            guestVisitService.revoke(v.getId(), new GuestVisitRevokeRequest(0L, REASON), fm.getEmail());
            assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "thu hồi không xoá ngay");
            verify(photoStorage, never()).removeObject(key);

            jobService.deleteDueBiometrics(OffsetDateTime.now());
            assertEquals(GuestBiometricStatus.DELETED, guest(g.getId()).getBiometricStatus(), "job kế tiếp xoá");
            assertFalse(embeddingRepository.existsById(g.getId()));
            verify(photoStorage).removeObject(key);
            assertSystemAudit(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC));
        } finally {
            // GuestSchemaTest đọc thẳng giá trị trong DB: trả về mặc định
            setConfig(ConfigKey.GUEST_FACE_RETENTION_HOURS, ConfigKey.GUEST_FACE_RETENTION_HOURS.getDefaultValue());
        }
    }

    @Test
    @DisplayName("GV-09/27: host huỷ lượt APPROVED khi đã bắt đầu -> còn ảnh; job chưa xoá tại cancelledAt + retention − 1 phút, xoá tại cancelledAt + retention (audit SYSTEM, ảnh xoá khỏi kho sau commit)")
    void deleteCancelledAfterStartAtRetention() throws Exception {
        int retention = systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS);
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(2), List.of(internalArea), "Khách bị huỷ giữa lượt");
        // expires_at như lúc gắn ảnh: end + retention, muộn hơn cancelledAt + retention -> chỉ nhánh CANCELLED xoá được
        Guest g = withPhoto(guestsOf(v.getId()).get(0), v.getEndTime().plusHours(retention));
        String key = g.getPhotoObjectKey();
        guestVisitService.cancel(v.getId(), new GuestVisitCancelRequest(0L, null), hostL2.getEmail());
        GuestVisit cancelled = visit(v.getId());
        assertEquals(GuestVisitStatus.CANCELLED, cancelled.getStatus());
        OffsetDateTime cancelledAt = cancelled.getCancelledAt();
        assertFalse(cancelledAt.isBefore(cancelled.getStartTime()), "tiền đề: huỷ sau giờ bắt đầu");
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "huỷ khi đã bắt đầu: không xoá ngay");

        OffsetDateTime mark = cancelledAt.plusHours(retention);
        jobService.deleteDueBiometrics(mark.minusMinutes(1));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus(), "cancelledAt + retention − 1 phút: chưa xoá");
        assertTrue(embeddingRepository.existsById(g.getId()));
        verify(photoStorage, never()).removeObject(key);
        assertTrue(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).isEmpty());

        jobService.deleteDueBiometrics(mark);
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.DELETED, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()));
        verify(photoStorage).removeObject(key);
        assertNull(after.getPendingDeleteObjectKey(), "xoá ảnh sau commit thành công -> bỏ đánh dấu");
        assertSystemAudit(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC));
    }

    @Test
    @DisplayName("GV-27: kho lỗi -> embedding vẫn xoá, khách DELETED, giữ đánh dấu cần xoá ảnh; lượt sau kho OK -> xoá ảnh, bỏ đánh dấu, không audit thêm")
    void minioFailureRetried() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(4), now.minusHours(2), List.of(internalArea), "Khách kho lỗi");
        Guest g = withPhoto(guestsOf(v.getId()).get(0), now.minusMinutes(5));
        String key = g.getPhotoObjectKey();
        doThrow(new RuntimeException("MinIO down")).when(photoStorage).removeObject(any());
        jobService.deleteDueBiometrics(now);
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.DELETED, after.getBiometricStatus());
        assertFalse(embeddingRepository.existsById(g.getId()), "embedding vẫn xoá");
        assertEquals(key, after.getPendingDeleteObjectKey(), "giữ đánh dấu cần xoá ảnh");
        assertTrue(audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).get(0).getNewValue()
                        .replace(" ", "").contains("\"photoDeletionScheduled\":true"),
                "snapshot ghi photoDeletionScheduled = true (xoá ảnh sau commit)");

        reset(photoStorage);
        jobService.retryPendingPhotoDeletions();
        assertNull(guest(g.getId()).getPendingDeleteObjectKey());
        verify(photoStorage).removeObject(key);
        jobService.retryPendingPhotoDeletions();
        verify(photoStorage, times(1)).removeObject(key);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size(), "không audit thêm");
    }

    @Test
    @DisplayName("GV-29/30: sau end + GUEST_RECORD_RETENTION_DAYS -> họ tên 'Khách đã ẩn danh', đơn vị NULL, audit ANONYMIZE SYSTEM (không chứa họ tên); chưa tới mốc giữ nguyên; idempotent")
    void anonymize() {
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit old = transactionTemplate.execute(tx -> {
            GuestVisit x = GuestVisit.builder().host(hostL2).purpose(PURPOSE).startTime(now.minusDays(91).minusHours(2))
                    .endTime(now.minusDays(91)).status(GuestVisitStatus.COMPLETED).reviewedBy(fm).reviewedAt(now.minusDays(92))
                    .closedAt(now.minusDays(91)).build();
            x.getAreas().add(internalArea);
            x.getGuests().add(Guest.builder().visit(x).fullName("Lê Văn Cũ").organization("Công ty XYZ").build());
            return guestVisitRepository.save(x);
        });
        GuestVisit recent = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusDays(89).minusHours(2), now.minusDays(89),
                List.of(internalArea), "Phạm Thị Mới");
        Guest g = guestsOf(old.getId()).get(0);
        jobService.anonymizeGuests(now);
        Guest after = guest(g.getId());
        assertEquals("Khách đã ẩn danh", after.getFullName());
        assertNull(after.getOrganization());
        assertNotNull(after.getAnonymizedAt());
        List<AuditLog> logs = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ANONYMIZE);
        assertSystemAudit(logs);
        assertFalse((logs.get(0).getOldValue() + logs.get(0).getNewValue()).contains("Lê Văn Cũ"), "audit không chứa họ tên");
        assertEquals("Phạm Thị Mới", guestsOf(recent.getId()).get(0).getFullName());
        jobService.anonymizeGuests(now);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ANONYMIZE).size());
    }
}
