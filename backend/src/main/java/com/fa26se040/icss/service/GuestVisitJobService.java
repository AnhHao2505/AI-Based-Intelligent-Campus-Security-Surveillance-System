package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guest.GuestAuditSnapshot;
import com.fa26se040.icss.dto.guest.GuestVisitAuditSnapshot;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.GuestFaceEmbeddingRepository;
import com.fa26se040.icss.repository.GuestRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * Job lượt khách (BR-GV-12, 25, 27, 29). Mỗi mục một transaction riêng (REQUIRES_NEW), khoá dòng lượt, actor SYSTEM.
 * Idempotent: chạy lại không đổi gì, không audit thêm. Snapshot audit không chứa ảnh / embedding / URL / họ tên.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestVisitJobService {

    public static final String SOURCE = "GUEST_VISIT_JOB";
    private static final Set<GuestVisitStatus> CLOSED_WITHOUT_VISIT = Set.of(
            GuestVisitStatus.CANCELLED, GuestVisitStatus.REVOKED, GuestVisitStatus.EXPIRED, GuestVisitStatus.REJECTED);

    private final GuestVisitRepository guestVisitRepository;
    private final GuestRepository guestRepository;
    private final GuestFaceEmbeddingRepository embeddingRepository;
    private final GuestBiometricService biometricService;
    private final GuestVisitService guestVisitService;
    private final AuditService auditService;
    private final SystemConfigService systemConfigService;
    private final PlatformTransactionManager transactionManager;

    /** Chạy đủ các bước theo thứ tự; một bước lỗi không chặn bước sau. */
    public void runAll(OffsetDateTime now) {
        safely("expire", () -> expirePendingVisits(now));
        safely("complete", () -> completeApprovedVisits(now));
        safely("delete-biometrics", () -> deleteDueBiometrics(now));
        safely("retry-photo-deletion", this::retryPendingPhotoDeletions);
        safely("anonymize", () -> anonymizeGuests(now));
    }

    /** BR-GV-12: PENDING khi now ≥ start -> EXPIRED, báo host. */
    public int expirePendingVisits(OffsetDateTime now) {
        int n = 0;
        for (UUID id : guestVisitRepository.findIdsByStatusAndStartTimeLessThanEqual(GuestVisitStatus.PENDING, now)) {
            n += inNewTx(() -> {
                GuestVisit v = guestVisitRepository.findByIdForUpdate(id).orElse(null);
                if (v == null || v.getStatus() != GuestVisitStatus.PENDING || v.getStartTime().isAfter(now)) {
                    return false;
                }
                close(v, GuestVisitStatus.EXPIRED, AuditAction.EXPIRE, now);
                guestVisitService.deleteAllBiometrics(v, now, AuditActor.system(SOURCE), "Lượt khách hết hạn chưa duyệt");
                User host = v.getHost();
                guestVisitService.notifyAfterCommit(v.getId(), NotificationType.GUEST_VISIT_EXPIRED, "Lượt khách hết hạn",
                        "Lượt khách vào " + GuestVisitService.areaNames(v) + " ("
                                + InAppNotificationService.formatTimeRange(v.getStartTime(), v.getEndTime())
                                + ") đã hết hạn vì chưa được duyệt trước giờ bắt đầu.", () -> List.of(host));
                return true;
            }) ? 1 : 0;
        }
        return n;
    }

    /** BR-GV-25: APPROVED khi now ≥ end -> COMPLETED. Sinh trắc giữ tới end + GUEST_FACE_RETENTION_HOURS. */
    public int completeApprovedVisits(OffsetDateTime now) {
        int n = 0;
        for (UUID id : guestVisitRepository.findIdsByStatusAndEndTimeLessThanEqual(GuestVisitStatus.APPROVED, now)) {
            n += inNewTx(() -> {
                GuestVisit v = guestVisitRepository.findByIdForUpdate(id).orElse(null);
                if (v == null || v.getStatus() != GuestVisitStatus.APPROVED || v.getEndTime().isAfter(now)) {
                    return false;
                }
                close(v, GuestVisitStatus.COMPLETED, AuditAction.COMPLETE, now);
                return true;
            }) ? 1 : 0;
        }
        return n;
    }

    /**
     * BR-GV-27: xoá ảnh + embedding tại mốc sớm nhất: end + GUEST_FACE_RETENTION_HOURS (config hiện hành) hoặc expires_at
     * của embedding; lượt CANCELLED / REVOKED / EXPIRED (và REJECTED) thì xoá ngay.
     */
    public int deleteDueBiometrics(OffsetDateTime now) {
        int retention = systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS);
        Set<UUID> ids = new LinkedHashSet<>(guestRepository.findIdsNeedingBiometricDeletion(
                GuestBiometricService.WITH_PHOTO, CLOSED_WITHOUT_VISIT, now.minusHours(retention)));
        ids.addAll(embeddingRepository.findGuestIdsExpiredAt(now));
        int n = 0;
        for (UUID guestId : ids) {
            n += inNewTx(() -> {
                Guest g = guestRepository.findById(guestId).orElse(null);
                if (g == null) {
                    return false;
                }
                guestVisitRepository.findByIdForUpdate(g.getVisit().getId());
                return biometricService.deleteBiometrics(g, now, AuditActor.system(SOURCE), "Hết thời hạn lưu sinh trắc của khách");
            }) ? 1 : 0;
        }
        return n;
    }

    /** BR-GV-27: thử xoá lại ảnh còn treo trên kho. Không audit thêm. */
    public int retryPendingPhotoDeletions() {
        int n = 0;
        for (UUID guestId : guestRepository.findIdsWithPendingPhotoDeletion()) {
            n += inNewTx(() -> {
                Guest g = guestRepository.findById(guestId).orElse(null);
                return g != null && biometricService.retryPendingPhotoDeletion(g);
            }) ? 1 : 0;
        }
        return n;
    }

    /** BR-GV-29: sau end + GUEST_RECORD_RETENTION_DAYS -> họ tên "Khách đã ẩn danh", đơn vị NULL. */
    public int anonymizeGuests(OffsetDateTime now) {
        int days = systemConfigService.getInt(ConfigKey.GUEST_RECORD_RETENTION_DAYS);
        int n = 0;
        for (UUID guestId : guestRepository.findIdsToAnonymize(now.minusDays(days))) {
            n += inNewTx(() -> {
                Guest g = guestRepository.findById(guestId).orElse(null);
                if (g == null || g.getAnonymizedAt() != null) {
                    return false;
                }
                guestVisitRepository.findByIdForUpdate(g.getVisit().getId());
                AuditActor actor = AuditActor.system(SOURCE);
                // sinh trắc phải đi trước ẩn danh (bình thường đã xoá từ end + retention)
                biometricService.deleteBiometrics(g, now, actor, "Ẩn danh khách");
                GuestAuditSnapshot before = GuestBiometricService.snapshot(g, null);
                g.setFullName(Guest.ANONYMIZED_NAME);
                g.setOrganization(null);
                g.setAnonymizedAt(now);
                guestRepository.save(g);
                auditService.record(AuditTargetType.GUEST, AuditAction.ANONYMIZE, g.getId().toString(), null, null,
                        before, GuestBiometricService.snapshot(g, null), "Hết thời hạn lưu thông tin khách", actor);
                return true;
            }) ? 1 : 0;
        }
        return n;
    }

    private void close(GuestVisit v, GuestVisitStatus to, AuditAction action, OffsetDateTime now) {
        GuestVisitAuditSnapshot before = GuestVisitService.snapshot(v);
        v.setStatus(to);
        v.setClosedAt(now);
        GuestVisitService.bumpVersion(v);
        guestVisitRepository.save(v);
        auditService.record(AuditTargetType.GUEST_VISIT, action, v.getId().toString(), null, null,
                before, GuestVisitService.snapshot(v), null, AuditActor.system(SOURCE));
    }

    private boolean inNewTx(Supplier<Boolean> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Boolean r = AuditContext.runAsSystem(SOURCE, () -> tx.execute(status -> work.get()));
            return Boolean.TRUE.equals(r);
        } catch (Exception ex) {
            log.error("[GUEST-JOB] item failed: {}", ex.getMessage(), ex);
            return false;
        }
    }

    private void safely(String step, Runnable r) {
        try {
            r.run();
        } catch (Exception ex) {
            log.error("[GUEST-JOB] step {} failed: {}", step, ex.getMessage(), ex);
        }
    }
}
