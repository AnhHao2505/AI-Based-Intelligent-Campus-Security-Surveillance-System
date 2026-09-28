package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guest.GuestAuditSnapshot;
import com.fa26se040.icss.dto.guest.GuestPhotoUrlResponse;
import com.fa26se040.icss.dto.guest.GuestResponse;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestFaceEmbedding;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.repository.GuestFaceEmbeddingRepository;
import com.fa26se040.icss.repository.GuestRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * ADMIN gắn ảnh khách đã đồng ý tại quầy (BR-GV-14..17) và xin URL xem ảnh (BR-GV-18).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestPhotoService {

    private static final int EMBEDDING_DIM = 512;

    private final GuestVisitRepository guestVisitRepository;
    private final GuestRepository guestRepository;
    private final GuestFaceEmbeddingRepository embeddingRepository;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;
    private final GuestPhotoStorageService photoStorage;
    private final GuestFaceEmbeddingClient faceClient;
    private final GuestBiometricService biometricService;
    private final AuditService auditService;

    /** Giới hạn kỹ thuật của ảnh đầu vào AI (cùng mức với ảnh đăng ký khuôn mặt người dùng), không phải tham số nghiệp vụ. */
    @Value("${icss.guest.photo-max-kb:350}")
    private int photoMaxKb;

    @Transactional
    public GuestResponse attach(UUID visitId, UUID guestId, MultipartFile file, Boolean consentConfirmed,
                                String consentNoticeVersion, String adminEmail) {
        User admin = userRepository.findByEmail(adminEmail).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        // BR-GV-15
        String currentVersion = systemConfigService.getString(ConfigKey.GUEST_CONSENT_NOTICE_VERSION);
        if (!Boolean.TRUE.equals(consentConfirmed) || consentNoticeVersion == null
                || !currentVersion.equals(consentNoticeVersion.trim())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_031, currentVersion);
        }
        byte[] bytes = readImage(file);
        String contentType = isPng(file) ? "image/png" : "image/jpeg";

        // BR-GV-14 (khoá dòng lượt: không để gắn ảnh chen giữa lúc thu hồi / huỷ)
        GuestVisit visit = guestVisitRepository.findByIdForUpdate(visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        Guest guest = guestRepository.findByIdAndVisitId(guestId, visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        OffsetDateTime now = OffsetDateTime.now();
        if (visit.getStatus() != GuestVisitStatus.APPROVED || !now.isBefore(visit.getEndTime())
                || guest.getBiometricStatus() == GuestBiometricStatus.DELETED) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_030);
        }

        // BR-GV-17: embedding 512d từ ai-service (endpoint không ghi face_data)
        GuestFaceEmbeddingClient.Result ai;
        try {
            ai = faceClient.extract(bytes, file.getOriginalFilename(), guest.getId().toString());
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] ai-service failed: {}", ex.getMessage());
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }
        if (ai == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }
        if (ai.faceCount() != 1) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_034, ai.faceCount());
        }
        if (ai.embedding() == null || ai.embedding().size() != EMBEDDING_DIM) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }

        // Ảnh cũ còn treo trên kho từ lần xoá lỗi trước: phải xoá được rồi mới gắn ảnh mới
        if (guest.getPendingDeleteObjectKey() != null && !biometricService.retryPendingPhotoDeletion(guest)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_036);
        }

        String key = "visits/" + visitId + "/" + guestId + "/" + UUID.randomUUID() + ("image/png".equals(contentType) ? ".png" : ".jpg");
        try {
            photoStorage.upload(key, bytes, contentType);
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] upload failed: {}", ex.getMessage());
            throw new GuestException(GuestErrorCode.ERR_GUEST_036);
        }
        removeOnRollback(key);

        AuditActor actor = AuditActor.user(admin);
        GuestAuditSnapshot before = GuestBiometricService.snapshot(guest, null);
        GuestFaceEmbedding embedding = embeddingRepository.findById(guestId).orElse(null);
        // BR-GV-16: gắn lại -> xoá ảnh + embedding cũ (audit)
        if (GuestBiometricService.WITH_PHOTO.contains(guest.getBiometricStatus())) {
            String oldKey = guest.getPhotoObjectKey();
            boolean removed = true;
            if (oldKey != null) {
                try {
                    photoStorage.removeObject(oldKey);
                } catch (Exception ex) {
                    removed = false;
                    guest.setPendingDeleteObjectKey(oldKey);
                }
            }
            auditService.record(AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC, guest.getId().toString(), null, null,
                    before, new GuestAuditSnapshot(guest.getId(), visitId, GuestBiometricStatus.DELETED,
                            guest.getConsentNoticeVersion(), removed, false),
                    "Gắn lại ảnh khách, xoá ảnh và embedding cũ", actor);
        }

        guest.setBiometricStatus(GuestBiometricStatus.PHOTO_READY);
        guest.setPhotoObjectKey(key);
        guest.setConsentConfirmedBy(admin);
        guest.setConsentConfirmedAt(now);
        guest.setConsentNoticeVersion(currentVersion);
        guest.setPhotoAttachedAt(now);
        guest.setBiometricDeletedAt(null);
        guestRepository.save(guest);

        OffsetDateTime expiresAt = visit.getEndTime().plusHours(systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS));
        String vector = toVector(ai.embedding());
        if (embedding != null) {
            // cùng khoá chính: thay tại chỗ, không xoá rồi chèn trong cùng persistence context
            embedding.setEmbedding(vector);
            embedding.setExpiresAt(expiresAt);
            embedding.setCreatedAt(now);
            embeddingRepository.save(embedding);
        } else {
            embeddingRepository.save(GuestFaceEmbedding.builder().guestId(guestId).embedding(vector).expiresAt(expiresAt).createdAt(now).build());
        }

        auditService.record(AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO, guest.getId().toString(), null, null,
                before, GuestBiometricService.snapshot(guest, null), null, actor);
        return new GuestResponse(guest.getId(), guest.getFullName(), guest.getOrganization(), guest.getBiometricStatus(),
                guest.getPhotoAttachedAt(), guest.getConsentNoticeVersion(), guest.getAnonymizedAt() != null);
    }

    /** BR-GV-18: URL xem ảnh có hạn; mỗi lần cấp ghi audit (snapshot không chứa URL). */
    @Transactional
    public GuestPhotoUrlResponse issueViewUrl(UUID visitId, UUID guestId, String adminEmail) {
        User admin = userRepository.findByEmail(adminEmail).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        Guest guest = guestRepository.findByIdAndVisitId(guestId, visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        if (!GuestBiometricService.WITH_PHOTO.contains(guest.getBiometricStatus()) || guest.getPhotoObjectKey() == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_037);
        }
        int ttl = systemConfigService.getInt(ConfigKey.GUEST_PHOTO_URL_TTL_SECONDS);
        String url;
        try {
            url = photoStorage.presignedGetUrl(guest.getPhotoObjectKey(), ttl);
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] presign failed: {}", ex.getMessage());
            throw new GuestException(GuestErrorCode.ERR_GUEST_036);
        }
        GuestAuditSnapshot snap = GuestBiometricService.snapshot(guest, null);
        auditService.record(AuditTargetType.GUEST, AuditAction.VIEW_PHOTO, guest.getId().toString(), null, null,
                snap, snap, null, AuditActor.user(admin));
        return new GuestPhotoUrlResponse(url, ttl);
    }

    private byte[] readImage(MultipartFile file) {
        boolean okType = file != null && !file.isEmpty() && (isPng(file) || isJpeg(file));
        if (!okType || file.getSize() > (long) photoMaxKb * 1024) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_033, photoMaxKb);
        }
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_033, photoMaxKb);
        }
    }

    private static boolean isPng(MultipartFile f) {
        String ct = f.getContentType() != null ? f.getContentType().toLowerCase() : "";
        String name = f.getOriginalFilename() != null ? f.getOriginalFilename().toLowerCase() : "";
        return ct.equals("image/png") || (ct.isEmpty() && name.endsWith(".png"));
    }

    private static boolean isJpeg(MultipartFile f) {
        String ct = f.getContentType() != null ? f.getContentType().toLowerCase() : "";
        String name = f.getOriginalFilename() != null ? f.getOriginalFilename().toLowerCase() : "";
        return ct.equals("image/jpeg") || ct.equals("image/jpg") || (ct.isEmpty() && (name.endsWith(".jpg") || name.endsWith(".jpeg")));
    }

    private static String toVector(List<Float> values) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (Float v : values) {
            sj.add(Float.toString(v));
        }
        return sj.toString();
    }

    /** Transaction rollback sau khi đã tải ảnh lên -> xoá object mới để không để lại ảnh mồ côi. */
    private void removeOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        photoStorage.removeObject(key);
                    } catch (Exception ex) {
                        log.warn("[GUEST-PHOTO] could not remove orphan object after rollback: {}", ex.getMessage());
                    }
                }
            }
        });
    }
}
