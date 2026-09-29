package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guest.GuestAuditSnapshot;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.GuestBiometricStatus;
import com.fa26se040.icss.repository.GuestFaceEmbeddingRepository;
import com.fa26se040.icss.repository.GuestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Xoá sinh trắc khách (BR-GV-27). Chạy trong transaction của thao tác gọi (huỷ, thu hồi, job).
 * Thứ tự: xoá embedding → khách DELETED → xoá ảnh trên kho; kho lỗi thì giữ pending_delete_object_key để job thử lại.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestBiometricService {

    public static final Set<GuestBiometricStatus> WITH_PHOTO = Set.of(GuestBiometricStatus.PHOTO_READY, GuestBiometricStatus.PHOTO_ONLY);

    private final GuestRepository guestRepository;
    private final GuestFaceEmbeddingRepository embeddingRepository;
    private final GuestPhotoStorageService photoStorage;
    private final AuditService auditService;

    /**
     * Xoá ảnh + embedding của khách nếu còn. Idempotent: khách không còn gì để xoá thì không làm gì, không audit.
     * @return true nếu có xoá (và đã ghi audit DELETE_BIOMETRIC)
     */
    @Transactional
    public boolean deleteBiometrics(Guest guest, OffsetDateTime now, AuditActor actor, String reason) {
        boolean hasEmbedding = embeddingRepository.existsById(guest.getId());
        if (!WITH_PHOTO.contains(guest.getBiometricStatus()) && !hasEmbedding) {
            return false;
        }
        GuestAuditSnapshot before = snapshot(guest, null);
        if (hasEmbedding) {
            embeddingRepository.deleteById(guest.getId());
        }
        String key = guest.getPhotoObjectKey();
        guest.setBiometricStatus(GuestBiometricStatus.DELETED);
        guest.setBiometricDeletedAt(now);
        guest.setPhotoObjectKey(null);
        boolean removed = true;
        if (key != null) {
            removed = tryRemove(key);
            if (!removed) {
                guest.setPendingDeleteObjectKey(key);
            }
        }
        guestRepository.save(guest);
        auditService.record(AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC, guest.getId().toString(), null, null,
                before, snapshot(guest, removed), reason, actor);
        return true;
    }

    /** BR-GV-27: thử xoá lại ảnh còn treo trên kho. Thành công -> bỏ đánh dấu. Không ghi audit thêm. */
    @Transactional
    public boolean retryPendingPhotoDeletion(Guest guest) {
        String key = guest.getPendingDeleteObjectKey();
        if (key == null) {
            return false;
        }
        if (!tryRemove(key)) {
            return false;
        }
        guest.setPendingDeleteObjectKey(null);
        guestRepository.save(guest);
        return true;
    }

    private boolean tryRemove(String key) {
        try {
            photoStorage.removeObject(key);
            return true;
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] Could not remove object from guest bucket, will retry: {}", ex.getMessage());
            return false;
        }
    }

    public static GuestAuditSnapshot snapshot(Guest g, Boolean photoRemoved) {
        return new GuestAuditSnapshot(g.getId(), g.getVisit() != null ? g.getVisit().getId() : null,
                g.getBiometricStatus(), g.getConsentNoticeVersion(), photoRemoved, g.getAnonymizedAt() != null);
    }
}
