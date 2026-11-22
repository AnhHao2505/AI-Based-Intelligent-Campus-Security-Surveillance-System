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
import java.util.UUID;

/**
 * Ảnh khách (BR-GV-14..18): host gắn ảnh khi tạo lượt ({@link GuestVisitService#create}) và thay ảnh khi lượt còn chờ
 * duyệt hoặc đã duyệt nhưng chưa tới giờ bắt đầu; host / FM / ADMIN xin URL xem ảnh có hạn.
 * Mỗi ảnh: JPG / PNG ≤ icss.guest.photo-max-kb, trích embedding 512d đúng 1 khuôn mặt, không trùng khuôn mặt người dùng
 * chưa xoá mềm (kể cả đang bị vô hiệu hoá), khách khác trong cùng lượt, hay khách của lượt chờ duyệt / đã duyệt chồng giờ
 * ({@link FaceDuplicateCheckService#findDuplicateForGuest}). Trùng tài khoản hoặc khách lượt khác: host chỉ nhận câu chung
 * ERR_GUEST_041, không lộ mã / tên / lượt (face-gate 2); trùng khách cùng lượt: ERR_GUEST_038 nêu tên khách đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestPhotoService {

    private final GuestVisitRepository guestVisitRepository;
    private final GuestRepository guestRepository;
    private final GuestFaceEmbeddingRepository embeddingRepository;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;
    private final GuestPhotoStorageService photoStorage;
    private final GuestFaceEmbeddingClient faceClient;
    private final GuestBiometricService biometricService;
    private final GuestPhotoDeletionService photoDeletion;
    private final AuditService auditService;
    // L3: gắn / thay ảnh -> báo bên nhận diện nạp lại kho khuôn mặt sau commit
    private final AiFaceCacheRefreshNotifier aiFaceCacheRefreshNotifier;
    private final FaceDuplicateCheckService duplicateCheck;

    /** Giới hạn kỹ thuật của ảnh đầu vào (property vận hành icss.guest.photo-max-kb), không phải tham số nghiệp vụ. */
    @Value("${icss.guest.photo-max-kb:2048}")
    private int photoMaxKb;

    /** Ảnh đã đọc, đúng định dạng và dung lượng, chưa trích khuôn mặt. */
    public record ImageFile(byte[] bytes, String contentType, String filename) {
    }

    /** Ảnh đã kiểm xong (đúng 1 khuôn mặt, không trùng ai), kèm embedding; chưa lưu. */
    public record CheckedPhoto(ImageFile image, List<Float> embedding) {
    }

    public int getPhotoMaxKb() {
        return photoMaxKb;
    }

    public String currentConsentVersion() {
        return systemConfigService.getString(ConfigKey.GUEST_CONSENT_NOTICE_VERSION);
    }

    /** BR-GV-15: người gửi xác nhận khách đã đồng ý theo thông báo phiên bản hiện hành. Trả phiên bản hiện hành. */
    public String requireConsent(Boolean consentConfirmed, String consentNoticeVersion) {
        String currentVersion = currentConsentVersion();
        if (!Boolean.TRUE.equals(consentConfirmed) || consentNoticeVersion == null
                || !currentVersion.equals(consentNoticeVersion.trim())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_031, currentVersion);
        }
        return currentVersion;
    }

    /** BR-GV-14: có ảnh, JPG / PNG, không quá photoMaxKb. Chưa gọi trích khuôn mặt. */
    public ImageFile readImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_020, "ảnh khuôn mặt");
        }
        boolean png = isPng(file);
        if (!(png || isJpeg(file)) || file.getSize() > (long) photoMaxKb * 1024) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_033, photoMaxKb);
        }
        try {
            return new ImageFile(file.getBytes(), png ? "image/png" : "image/jpeg", file.getOriginalFilename());
        } catch (Exception ex) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_033, photoMaxKb);
        }
    }

    /**
     * BR-GV-17: embedding 512d từ dịch vụ trích khuôn mặt (endpoint không ghi face_data), đúng 1 khuôn mặt;
     * rồi kiểm trùng với người dùng chưa xoá mềm (kể cả đang bị vô hiệu hoá), khách khác trong cùng lượt và khách của lượt
     * PENDING / APPROVED khác có khung giờ giao với lượt này.
     *
     * @param logCode        mã gửi kèm để bên kia ghi log (id khách, không gửi họ tên)
     * @param visit          lượt của khách (lấy id + khung giờ; khi tạo lượt, khách cùng đơn so bằng requireDistinctWithinVisit)
     * @param excludeGuestId khách đang thay ảnh (không so với chính mình), null khi tạo mới
     */
    public CheckedPhoto extractAndCheck(ImageFile image, String logCode, GuestVisit visit, UUID excludeGuestId) {
        GuestFaceEmbeddingClient.Result result;
        try {
            result = faceClient.extract(image.bytes(), image.filename(), logCode);
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] face extraction failed: {}", ex.getMessage());
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }
        if (result == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }
        if (result.faceCount() != 1) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_034, result.faceCount());
        }
        if (result.embedding() == null || result.embedding().size() != FaceDuplicateCheckService.EMBEDDING_DIM) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_035);
        }
        duplicateCheck.findDuplicateForGuest(result.embedding(), visit.getId(), visit.getStartTime(), visit.getEndTime(),
                excludeGuestId).ifPresent(match -> {
            // face-gate 2: host không được biết khuôn mặt thuộc tài khoản nào hay khách lượt nào
            if (match.kind() == FaceDuplicateCheckService.Kind.GUEST_SAME_VISIT) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_038, match.describe());
            }
            throw new GuestException(GuestErrorCode.ERR_GUEST_041);
        });
        return new CheckedPhoto(image, result.embedding());
    }

    /**
     * Các khách trong cùng một lượt chưa có trong DB lúc kiểm: so ảnh mới với ảnh của các khách trước đó trong lượt
     * bằng cùng cosine + ngưỡng. Trùng -> ERR_GUEST_038 nêu tên khách kia.
     */
    public void requireDistinctWithinVisit(CheckedPhoto photo, List<CheckedPhoto> earlier, List<Guest> earlierGuests) {
        double threshold = duplicateCheck.threshold();
        for (int j = 0; j < earlier.size(); j++) {
            if (FaceDuplicateCheckService.cosine(photo.embedding(), earlier.get(j).embedding()) >= threshold) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_038,
                        "khách " + earlierGuests.get(j).getFullName() + " trong cùng lượt này");
            }
        }
    }

    /**
     * Lưu ảnh vào kho riêng + embedding (expires = end + GUEST_FACE_RETENTION_HOURS), ghi người xác nhận đồng ý và
     * audit ATTACH_PHOTO. Khách đã có ảnh (thay ảnh, BR-GV-16): audit DELETE_BIOMETRIC cho ảnh cũ, object cũ xoá sau commit.
     * Chạy trong transaction của người gọi; rollback thì object mới bị gỡ khỏi kho.
     */
    void store(GuestVisit visit, Guest guest, CheckedPhoto photo, User actor, String consentVersion, OffsetDateTime now) {
        ImageFile image = photo.image();
        String key = "visits/" + visit.getId() + "/" + guest.getId() + "/" + UUID.randomUUID()
                + ("image/png".equals(image.contentType()) ? ".png" : ".jpg");
        try {
            photoStorage.upload(key, image.bytes(), image.contentType());
        } catch (Exception ex) {
            log.warn("[GUEST-PHOTO] upload failed: {}", ex.getMessage());
            throw new GuestException(GuestErrorCode.ERR_GUEST_036);
        }
        removeOnRollback(key);

        AuditActor auditActor = AuditActor.user(actor);
        GuestAuditSnapshot before = GuestBiometricService.snapshot(guest, null);
        GuestFaceEmbedding embedding = embeddingRepository.findById(guest.getId()).orElse(null);
        // BR-GV-16: thay ảnh -> xoá ảnh + embedding cũ (audit)
        if (GuestBiometricService.WITH_PHOTO.contains(guest.getBiometricStatus())) {
            String oldKey = guest.getPhotoObjectKey();
            if (oldKey != null) {
                // A9: chỉ đánh dấu trong transaction; xoá object cũ SAU commit (rollback -> ảnh cũ còn nguyên)
                guest.setPendingDeleteObjectKey(oldKey);
                photoDeletion.deleteAfterCommit(guest.getId(), oldKey);
            }
            auditService.record(AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC, guest.getId().toString(), null, null,
                    before, new GuestAuditSnapshot(guest.getId(), visit.getId(), GuestBiometricStatus.DELETED,
                            guest.getConsentNoticeVersion(), oldKey != null, false),
                    "Thay ảnh khách, xoá ảnh và embedding cũ", auditActor);
        }

        guest.setBiometricStatus(GuestBiometricStatus.PHOTO_READY);
        guest.setPhotoObjectKey(key);
        guest.setConsentConfirmedBy(actor);
        guest.setConsentConfirmedAt(now);
        guest.setConsentNoticeVersion(consentVersion);
        guest.setPhotoAttachedAt(now);
        guest.setBiometricDeletedAt(null);
        guestRepository.save(guest);

        OffsetDateTime expiresAt = visit.getEndTime().plusHours(systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS));
        String vector = FaceDuplicateCheckService.toVector(photo.embedding());
        if (embedding != null) {
            // cùng khoá chính: thay tại chỗ, không xoá rồi chèn trong cùng persistence context
            embedding.setEmbedding(vector);
            embedding.setExpiresAt(expiresAt);
            embedding.setCreatedAt(now);
            embeddingRepository.save(embedding);
        } else {
            embeddingRepository.save(GuestFaceEmbedding.builder().guestId(guest.getId()).embedding(vector)
                    .expiresAt(expiresAt).createdAt(now).build());
        }

        auditService.record(AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO, guest.getId().toString(), null, null,
                before, GuestBiometricService.snapshot(guest, null), null, auditActor);
    }

    /**
     * Host thay ảnh khách của chính lượt mình (A5): lượt PENDING, hoặc APPROVED mà chưa tới giờ bắt đầu.
     * Người khác (kể cả FM) -> 404 như xem lượt. Thay ảnh tăng version lượt để FM đang xem bản cũ phải tải lại
     * trước khi duyệt / từ chối / thu hồi (ERR_GUEST_016).
     */
    @Transactional
    public GuestResponse attach(UUID visitId, UUID guestId, MultipartFile file, Boolean consentConfirmed,
                                String consentNoticeVersion, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
        String consentVersion = requireConsent(consentConfirmed, consentNoticeVersion);
        ImageFile image = readImage(file);

        // khoá dòng lượt: không để thay ảnh chen giữa lúc duyệt / từ chối / huỷ / thu hồi
        GuestVisit visit = guestVisitRepository.findByIdForUpdate(visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
        if (!visit.getHost().getId().equals(actor.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_001);
        }
        Guest guest = guestRepository.findByIdAndVisitId(guestId, visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        OffsetDateTime now = OffsetDateTime.now();
        boolean editable = visit.getStatus() == GuestVisitStatus.PENDING || visit.getStatus() == GuestVisitStatus.APPROVED;
        if (!editable || !now.isBefore(visit.getStartTime()) || guest.getBiometricStatus() == GuestBiometricStatus.DELETED) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_039);
        }

        CheckedPhoto photo = extractAndCheck(image, guest.getId().toString(), visit, guest.getId());

        // Ảnh cũ còn treo trên kho từ lần xoá lỗi trước: phải xoá được rồi mới gắn ảnh mới
        // (xoá trước commit ở đây vẫn an toàn: object đó đã được DB đánh dấu là phải xoá)
        if (guest.getPendingDeleteObjectKey() != null && !biometricService.retryPendingPhotoDeletion(guest)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_036);
        }

        store(visit, guest, photo, actor, consentVersion, now);
        GuestVisitService.bumpVersion(visit);
        guestVisitRepository.save(visit);
        aiFaceCacheRefreshNotifier.notifyAfterCommit();
        return new GuestResponse(guest.getId(), guest.getFullName(), guest.getOrganization(), guest.getBiometricStatus(),
                guest.getPhotoAttachedAt(), guest.getConsentNoticeVersion(), guest.getAnonymizedAt() != null);
    }

    /**
     * BR-GV-18: URL xem ảnh có hạn; mỗi lần cấp ghi audit VIEW_PHOTO (snapshot không chứa URL).
     * FM và ADMIN xem mọi lượt; host chỉ xem khách của lượt mình (người khác -> 404 như xem lượt).
     */
    @Transactional
    public GuestPhotoUrlResponse issueViewUrl(UUID visitId, UUID guestId, String viewerEmail) {
        User viewer = userRepository.findByEmail(viewerEmail).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
        Guest guest = guestRepository.findByIdAndVisitId(guestId, visitId).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_032));
        boolean staff = viewer.getRole() == Role.FACILITY_MANAGER || viewer.getRole() == Role.ADMIN;
        if (!staff && !guest.getVisit().getHost().getId().equals(viewer.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_001);
        }
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
                snap, snap, null, AuditActor.user(viewer));
        return new GuestPhotoUrlResponse(url, ttl);
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
