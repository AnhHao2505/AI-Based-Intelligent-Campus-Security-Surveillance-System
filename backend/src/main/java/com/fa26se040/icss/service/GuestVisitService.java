package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guest.*;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Lượt khách: host tạo kèm ảnh khách / xem / huỷ (BR-GV-01..06, 09, 14..17); FM duyệt / từ chối / thu hồi (BR-GV-10..13).
 * Mọi chuyển trạng thái khoá dòng lượt (PESSIMISTIC_WRITE) và so version client gửi (BR-GV-32).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestVisitService {

    public static final String REF_TYPE_GUEST_VISIT = "GUEST_VISIT";

    private final GuestVisitRepository guestVisitRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final SystemConfigService systemConfigService;
    private final ReasonValidator reasonValidator;
    private final GuestRules rules;
    private final GuestBiometricService biometricService;
    private final AuditService auditService;
    private final ObjectProvider<InAppNotificationService> notificationServiceProvider;
    // L3: huỷ lượt đã duyệt / thu hồi -> báo AI nạp lại kho khuôn mặt sau commit
    private final AiFaceCacheRefreshNotifier aiFaceCacheRefreshNotifier;
    private final GuestPhotoService photoService;

    // ================================================================== tạo (BR-GV-01..06, 14..17)

    /**
     * Host tạo lượt kèm ảnh của mọi khách (photos[i] ứng với guests[i]) và xác nhận từng khách đã đồng ý.
     * Một transaction: ảnh của một khách lỗi (thiếu, sai định dạng, quá dung lượng, không / nhiều khuôn mặt, trùng khuôn mặt,
     * dịch vụ trích khuôn mặt lỗi) -> báo đúng khách đó, không tạo gì (ảnh đã tải lên kho bị gỡ khi rollback).
     */
    @Transactional
    public GuestVisitResponse create(GuestVisitCreateRequest req, List<MultipartFile> photos, String actorEmail) {
        User host = currentUser(actorEmail);
        OffsetDateTime now = OffsetDateTime.now();

        // BR-GV-01
        if (!rules.hostEligible(host)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_002, rules.hostMinLevel());
        }
        // BR-GV-02
        List<Guest> guests = buildGuests(req.guests());
        // BR-GV-03 (N1, V120): độ dài đọc từ GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH
        String purpose = req.purpose() != null ? req.purpose().trim() : "";
        com.fa26se040.icss.dto.guest.GuestPurposeLimits purposeLimits = rules.purposeLimits();
        if (purpose.length() < purposeLimits.minLength() || purpose.length() > purposeLimits.maxLength()) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_006, purposeLimits.minLength(), purposeLimits.maxLength());
        }
        // BR-GV-05
        validateWindow(req.startTime(), req.endTime(), now, true);
        // BR-GV-04, 06
        List<Area> areas = loadAreas(req.areaIds());
        for (Area area : areas) {
            checkArea(area);
            if (!rules.hostCoversArea(host, area, req.startTime(), req.endTime())) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_014, area.getName());
            }
        }

        // BR-GV-14, 15: mọi khách có ảnh + host xác nhận đồng ý; kiểm phần không tốn kém cho mọi khách trước khi trích khuôn mặt
        String consentVersion = photoService.currentConsentVersion();
        List<GuestPhotoService.ImageFile> images = readGuestImages(req, guests, photos);

        GuestVisit visit = GuestVisit.builder()
                .host(host)
                .purpose(purpose)
                .startTime(req.startTime())
                .endTime(req.endTime())
                .status(GuestVisitStatus.PENDING)
                .build();
        visit.getAreas().addAll(areas);
        for (Guest g : guests) {
            g.setVisit(visit);
            visit.getGuests().add(g);
        }
        GuestVisit saved = guestVisitRepository.save(visit);

        // BR-GV-17 + kiểm trùng: trích khuôn mặt từng khách (đã có id để ghi log), so với người dùng đang hoạt động,
        // khách của lượt chờ duyệt / đã duyệt chồng giờ, và các khách trước đó trong cùng lượt
        List<GuestPhotoService.CheckedPhoto> checked = new ArrayList<>();
        for (int i = 0; i < guests.size(); i++) {
            Guest g = guests.get(i);
            try {
                GuestPhotoService.CheckedPhoto photo = photoService.extractAndCheck(images.get(i), g.getId().toString(), saved, null);
                photoService.requireDistinctWithinVisit(photo, checked, guests.subList(0, i));
                checked.add(photo);
            } catch (GuestException ex) {
                throw GuestException.forGuest(i, g.getFullName(), ex);
            }
        }

        auditService.record(AuditTargetType.GUEST_VISIT, AuditAction.CREATE, saved.getId().toString(), null, null,
                null, snapshot(saved), null, AuditActor.user(host));
        // người xác nhận đồng ý = host; mỗi khách một audit ATTACH_PHOTO (actor host)
        for (int i = 0; i < guests.size(); i++) {
            try {
                photoService.store(saved, guests.get(i), checked.get(i), host, consentVersion, now);
            } catch (GuestException ex) {
                throw GuestException.forGuest(i, guests.get(i).getFullName(), ex);
            }
        }

        // BR-GV-33: mọi FM đang hoạt động
        String message = host.getFullName() + " (" + host.getUserCode() + ") mời " + guests.size() + " khách vào "
                + areaNames(saved) + " (" + InAppNotificationService.formatTimeRange(saved.getStartTime(), saved.getEndTime()) + ").";
        notifyAfterCommit(saved.getId(), NotificationType.GUEST_VISIT_PENDING, "Lượt khách mới chờ duyệt", message,
                () -> userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER));
        return toResponse(saved);
    }

    /**
     * A1, A2: ảnh thứ i thuộc khách thứ i. Mỗi khách phải tick đồng ý theo phiên bản thông báo hiện hành (BR-GV-15) và có
     * ảnh JPG / PNG trong giới hạn dung lượng. Lỗi -> nêu đúng khách (data.guestIndex).
     */
    private List<GuestPhotoService.ImageFile> readGuestImages(GuestVisitCreateRequest req, List<Guest> guests,
                                                              List<MultipartFile> photos) {
        int count = photos == null ? 0 : photos.size();
        if (count > guests.size()) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_040, count, guests.size());
        }
        List<GuestPhotoService.ImageFile> images = new ArrayList<>();
        for (int i = 0; i < guests.size(); i++) {
            try {
                photoService.requireConsent(req.guests().get(i).consentConfirmed(), req.consentNoticeVersion());
                images.add(photoService.readImage(i < count ? photos.get(i) : null));
            } catch (GuestException ex) {
                throw GuestException.forGuest(i, guests.get(i).getFullName(), ex);
            }
        }
        return images;
    }

    private List<Guest> buildGuests(List<GuestInput> inputs) {
        int max = systemConfigService.getInt(ConfigKey.GUEST_MAX_PER_VISIT);
        if (inputs == null || inputs.isEmpty() || inputs.size() > max) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_003, max);
        }
        List<Guest> guests = new ArrayList<>();
        for (GuestInput in : inputs) {
            String name = in != null && in.fullName() != null ? in.fullName().trim() : "";
            if (name.length() < 2 || name.length() > 100) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_004);
            }
            String org = in.organization() != null ? in.organization().trim() : null;
            if (org != null && org.isEmpty()) {
                org = null;
            }
            if (org != null && org.length() > 200) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_005);
            }
            guests.add(Guest.builder().fullName(name).organization(org).build());
        }
        return guests;
    }

    /** BR-GV-05. requireFuture = false khi duyệt lại (BR-GV-11 trừ start > now). */
    void validateWindow(OffsetDateTime start, OffsetDateTime end, OffsetDateTime now, boolean requireFuture) {
        if (start == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_020, "startTime");
        }
        if (end == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_020, "endTime");
        }
        if (requireFuture && !start.isAfter(now)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_010);
        }
        if (!start.isBefore(end)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_011);
        }
        int maxHours = systemConfigService.getInt(ConfigKey.GUEST_VISIT_MAX_HOURS);
        if (Duration.between(start, end).compareTo(Duration.ofHours(maxHours)) > 0) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_012, maxHours);
        }
        int maxDays = systemConfigService.getInt(ConfigKey.GUEST_MAX_ADVANCE_DAYS);
        if (start.isAfter(now.plusDays(maxDays))) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_013, maxDays);
        }
    }

    private List<Area> loadAreas(List<UUID> areaIds) {
        if (areaIds == null || areaIds.isEmpty() || areaIds.contains(null) || new HashSet<>(areaIds).size() != areaIds.size()) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_007);
        }
        List<Area> areas = new ArrayList<>();
        for (UUID id : areaIds) {
            Area area = areaRepository.findById(id).orElse(null);
            if (area == null) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_008, id);
            }
            areas.add(area);
        }
        return areas;
    }

    /** BR-GV-04 cho một khu vực. */
    void checkArea(Area area) {
        if (!rules.areaActive(area)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_008, area.getName());
        }
        if (!rules.areaTypeAllowed(area)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_009, area.getName());
        }
    }

    /**
     * BR-GV-04: khu vực chọn được trong form lượt khách — cùng điều kiện với checkArea (đang hoạt động, chưa xoá mềm,
     * loại Bảo mật, V83).
     * BR-GV-06 (host phủ khu vực) phụ thuộc khung giờ: khi có đủ startTime và endTime thì mỗi khu vực kèm hostCanInvite
     * và reasonCode, tính bằng đúng GuestRules.hostEligible + hostCoversArea mà bước tạo lượt dùng (ERR_GUEST_002 /
     * ERR_GUEST_014). Thiếu một trong hai thì không tính (giữ như trước). Bước tạo lượt vẫn kiểm lại.
     */
    @Transactional(readOnly = true)
    public List<com.fa26se040.icss.dto.guest.GuestSelectableAreaResponse> listSelectableAreas(
            String actorEmail, OffsetDateTime startTime, OffsetDateTime endTime) {
        boolean withWindow = startTime != null && endTime != null;
        if (withWindow && !startTime.isBefore(endTime)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_011);
        }
        User host = withWindow ? currentUser(actorEmail) : null;
        boolean eligible = withWindow && rules.hostEligible(host);
        return areaRepository.findAvailableForRequest(GuestRules.GUEST_AREA_LEVELS).stream()
                .filter(a -> rules.areaActive(a) && rules.areaTypeAllowed(a))
                .map(a -> {
                    Boolean canInvite = null;
                    String reasonCode = null;
                    if (withWindow) {
                        if (!eligible) {
                            canInvite = false;
                            reasonCode = GuestErrorCode.ERR_GUEST_002.getCode();
                        } else {
                            canInvite = rules.hostCoversArea(host, a, startTime, endTime);
                            reasonCode = canInvite ? null : GuestErrorCode.ERR_GUEST_014.getCode();
                        }
                    }
                    return new com.fa26se040.icss.dto.guest.GuestSelectableAreaResponse(
                            a.getId(), a.getName(), a.getAreaLevel(), a.getBuilding(), a.getFloor(), canInvite, reasonCode);
                })
                .toList();
    }

    /** B-04: điều kiện mời khách của người gọi — cùng GuestRules.hostEligible mà bước tạo lượt dùng (ERR_GUEST_002). */
    @Transactional(readOnly = true)
    public com.fa26se040.icss.dto.guest.GuestHostEligibilityResponse eligibility(String actorEmail) {
        User me = currentUser(actorEmail);
        return new com.fa26se040.icss.dto.guest.GuestHostEligibilityResponse(
                rules.hostEligible(me), me.getAccessLevel(), rules.hostMinLevel(),
                photoService.currentConsentVersion(), photoService.getPhotoMaxKb());
    }

    // ================================================================== xem

    @Transactional(readOnly = true)
    public Page<GuestVisitResponse> listMine(String actorEmail, Pageable pageable) {
        User host = currentUser(actorEmail);
        return guestVisitRepository.findByHostIdOrderByCreatedAtDesc(host.getId(), pageable).map(this::toResponse);
    }

    /** Host xem lượt của mình; FM và ADMIN xem mọi lượt (duyệt / xem ảnh). Người khác -> 404 (không lộ lượt tồn tại). */
    @Transactional(readOnly = true)
    public GuestVisitResponse get(UUID id, String actorEmail) {
        User viewer = currentUser(actorEmail);
        GuestVisit visit = guestVisitRepository.findById(id).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
        boolean staff = viewer.getRole() == Role.FACILITY_MANAGER || viewer.getRole() == Role.ADMIN;
        if (!staff && !visit.getHost().getId().equals(viewer.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_001);
        }
        return toResponse(visit);
    }

    // ================================================================== huỷ (BR-GV-09)

    @Transactional
    public GuestVisitResponse cancel(UUID id, GuestVisitCancelRequest req, String actorEmail) {
        User host = currentUser(actorEmail);
        Long version = req != null ? req.version() : null;
        if (version == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_015);
        }
        String reason = optionalReason(req.reason());

        GuestVisit visit = lockVisit(id);
        if (!visit.getHost().getId().equals(host.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_001);
        }
        checkVersion(visit, version);
        GuestVisitStatus from = visit.getStatus();
        if (from != GuestVisitStatus.PENDING && from != GuestVisitStatus.APPROVED) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_017, from, "huỷ");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!now.isBefore(visit.getEndTime())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_018, "huỷ");
        }

        GuestVisitAuditSnapshot before = snapshot(visit);
        visit.setStatus(GuestVisitStatus.CANCELLED);
        visit.setCancelledAt(now);
        visit.setCancelReason(reason);
        bumpVersion(visit);
        guestVisitRepository.save(visit);
        AuditActor actor = AuditActor.user(host);
        auditService.record(AuditTargetType.GUEST_VISIT, AuditAction.CANCEL, visit.getId().toString(), null, null,
                before, snapshot(visit), reason, actor);
        // A6: host gắn ảnh ngay khi tạo lượt -> huỷ lượt chờ duyệt (khách chưa bao giờ có quyền) cũng xoá sinh trắc ngay
        if (from == GuestVisitStatus.PENDING) {
            deleteAllBiometrics(visit, now, actor, "Lượt khách bị huỷ");
        }
        // BR-GV-09, BR-194 (BR-GV-27): huỷ lượt đã duyệt trước giờ bắt đầu -> khách chưa từng có quyền, xoá sinh trắc ngay;
        // huỷ khi lượt đã bắt đầu -> hết quyền như thu hồi: giữ GUEST_FACE_RETENTION_HOURS kể từ cancelledAt rồi job mới xoá
        if (from == GuestVisitStatus.APPROVED) {
            if (now.isBefore(visit.getStartTime())) {
                deleteAllBiometrics(visit, now, actor, "Lượt khách bị huỷ");
            }
            aiFaceCacheRefreshNotifier.notifyAfterCommit();
        }
        return toResponse(visit);
    }

    // ================================================================== FM (BR-GV-10..13)

    @Transactional(readOnly = true)
    public Page<GuestVisitResponse> list(GuestVisitStatus status, Pageable pageable) {
        Page<GuestVisit> page = status != null
                ? guestVisitRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : guestVisitRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(this::toResponse);
    }

    @Transactional
    public GuestVisitResponse review(UUID id, GuestVisitReviewRequest req, String actorEmail) {
        User reviewer = currentUser(actorEmail);
        if (req == null || req.version() == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_015);
        }
        GuestVisitStatus decision;
        if ("APPROVED".equals(req.decision())) {
            decision = GuestVisitStatus.APPROVED;
        } else if ("REJECTED".equals(req.decision())) {
            decision = GuestVisitStatus.REJECTED;
        } else {
            throw new GuestException(GuestErrorCode.ERR_GUEST_024);
        }
        String reason = decision == GuestVisitStatus.REJECTED ? requiredReason(req.reason()) : optionalReason(req.reason());

        GuestVisit visit = lockVisit(id);
        if (visit.getHost().getId().equals(reviewer.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_022);
        }
        checkVersion(visit, req.version());
        if (visit.getStatus() != GuestVisitStatus.PENDING) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_017, visit.getStatus(), decision == GuestVisitStatus.APPROVED ? "duyệt" : "từ chối");
        }
        OffsetDateTime now = OffsetDateTime.now();
        // BR-GV-12: tới giờ bắt đầu thì không duyệt được nữa (job sẽ chuyển EXPIRED)
        if (!now.isBefore(visit.getStartTime())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_023);
        }
        // BR-GV-11: duyệt thì kiểm lại 01, 04, 05 (trừ start > now), 06
        if (decision == GuestVisitStatus.APPROVED) {
            String violation = recheckForApproval(visit, now);
            if (violation != null) {
                throw new GuestException(GuestErrorCode.ERR_GUEST_021, violation);
            }
        }

        GuestVisitAuditSnapshot before = snapshot(visit);
        visit.setStatus(decision);
        visit.setReviewedBy(reviewer);
        visit.setReviewedAt(now);
        visit.setReviewReason(reason);
        bumpVersion(visit);
        guestVisitRepository.save(visit);
        auditService.record(AuditTargetType.GUEST_VISIT, decision == GuestVisitStatus.APPROVED ? AuditAction.APPROVE : AuditAction.REJECT,
                visit.getId().toString(), null, null, before, snapshot(visit), reason, AuditActor.user(reviewer));
        // A6, BR-194 (BR-GV-27): từ chối -> khách chưa từng có quyền, xoá ảnh + embedding ngay (audit DELETE_BIOMETRIC)
        if (decision == GuestVisitStatus.REJECTED) {
            deleteAllBiometrics(visit, now, AuditActor.user(reviewer), "Lượt khách bị từ chối");
        }

        User host = visit.getHost();
        String range = InAppNotificationService.formatTimeRange(visit.getStartTime(), visit.getEndTime());
        if (decision == GuestVisitStatus.APPROVED) {
            notifyAfterCommit(visit.getId(), NotificationType.GUEST_VISIT_APPROVED, "Lượt khách đã được duyệt",
                    "Lượt khách vào " + areaNames(visit) + " (" + range + ") đã được duyệt.", () -> List.of(host));
        }
        // L3: ảnh khách gắn lúc tạo (lượt còn PENDING, kho khuôn mặt chưa lấy) -> duyệt xong báo bên nhận diện nạp lại ngay,
        // không chờ chu kỳ nạp định kỳ
        if (decision == GuestVisitStatus.APPROVED && visit.getGuests().stream()
                .anyMatch(g -> g.getBiometricStatus() == GuestBiometricStatus.PHOTO_READY)) {
            aiFaceCacheRefreshNotifier.notifyAfterCommit();
        }
        // Host gắn ảnh ngay khi tạo lượt: chỉ lượt tạo trước thay đổi này (còn khách chưa có ảnh) mới cần nhắc.
        // Người gắn / thay ảnh là host (ADMIN không gắn ảnh nữa) -> nhắc host, mở Chi tiết lượt để gắn trước giờ bắt đầu
        long missingPhotos = visit.getGuests().stream()
                .filter(g -> g.getBiometricStatus() != GuestBiometricStatus.PHOTO_READY).count();
        if (decision == GuestVisitStatus.APPROVED && missingPhotos > 0) {
            notifyAfterCommit(visit.getId(), NotificationType.GUEST_PHOTO_REQUIRED, "Cần gắn ảnh khách",
                    "Lượt khách vào " + areaNames(visit) + " (" + range + ") đã được duyệt, còn " + missingPhotos + "/"
                            + visit.getGuests().size() + " khách chưa có ảnh. Gắn ảnh trong Chi tiết lượt trước giờ bắt đầu.",
                    () -> List.of(host));
        }
        if (decision == GuestVisitStatus.REJECTED) {
            notifyAfterCommit(visit.getId(), NotificationType.GUEST_VISIT_REJECTED, "Lượt khách bị từ chối",
                    "Lượt khách vào " + areaNames(visit) + " (" + range + ") bị từ chối. Lý do: " + reason + ".", () -> List.of(host));
        }
        return toResponse(visit);
    }

    @Transactional
    public GuestVisitResponse revoke(UUID id, GuestVisitRevokeRequest req, String actorEmail) {
        User fmUser = currentUser(actorEmail);
        if (req == null || req.version() == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_015);
        }
        String reason = requiredReason(req.reason());

        GuestVisit visit = lockVisit(id);
        if (visit.getHost().getId().equals(fmUser.getId())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_022);
        }
        checkVersion(visit, req.version());
        if (visit.getStatus() != GuestVisitStatus.APPROVED) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_017, visit.getStatus(), "thu hồi");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!now.isBefore(visit.getEndTime())) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_018, "thu hồi");
        }

        applyRevoke(visit, fmUser, reason, now);
        return toResponse(visit);
    }

    /**
     * Thu hồi lượt đã duyệt — dùng chung cho FM thu hồi (BR-GV-13) và ADMIN vô hiệu hoá khu vực (BR-AD-09).
     * Không xoá sinh trắc tại đây: job xoá tại revokedAt + GUEST_FACE_RETENTION_HOURS (BR-194 (BR-GV-27)). Báo host sau commit.
     */
    private void applyRevoke(GuestVisit visit, User revokedBy, String reason, OffsetDateTime now) {
        GuestVisitAuditSnapshot before = snapshot(visit);
        visit.setStatus(GuestVisitStatus.REVOKED);
        visit.setRevokedBy(revokedBy);
        visit.setRevokedAt(now);
        visit.setRevokeReason(reason);
        bumpVersion(visit);
        guestVisitRepository.save(visit);
        AuditActor actor = AuditActor.user(revokedBy);
        auditService.record(AuditTargetType.GUEST_VISIT, AuditAction.REVOKE, visit.getId().toString(), null, null,
                before, snapshot(visit), reason, actor);
        // BR-GV-13, BR-194 (BR-GV-27): thu hồi -> hết quyền ngay (check-entry trả VISIT_NOT_ACTIVE, AI bỏ khách khỏi kho khuôn mặt);
        // ảnh + embedding giữ thêm GUEST_FACE_RETENTION_HOURS kể từ revokedAt rồi job mới xoá
        aiFaceCacheRefreshNotifier.notifyAfterCommit();
        User host = visit.getHost();
        notifyAfterCommit(visit.getId(), NotificationType.GUEST_VISIT_REVOKED, "Lượt khách bị thu hồi",
                "Lượt khách vào " + areaNames(visit) + " (" + InAppNotificationService.formatTimeRange(visit.getStartTime(), visit.getEndTime())
                        + ") đã bị thu hồi. Lý do: " + reason + ".", () -> List.of(host));
    }

    // ================================================================== vô hiệu hoá khu vực (Step 6, BR-AD-09)

    /**
     * ADMIN vô hiệu hoá khu vực: lượt khách chưa kết thúc chứa khu vực -> PENDING thành CANCELLED,
     * APPROVED thành REVOKED (revoked_by = ADMIN) qua đúng luồng thu hồi của FM.
     * Chạy trong transaction vô hiệu hoá (khu vực đã khoá); lỗi ở đây làm rollback cả thao tác.
     * Khoá từng lượt rồi xét lại: lượt vừa bị thao tác khác đổi trạng thái hoặc đã kết thúc thì bỏ qua.
     * Host của lượt PENDING bị huỷ nhận GUEST_VISIT_CANCELLED_BY_SYSTEM (H2); lượt APPROVED bị thu hồi nhận GUEST_VISIT_REVOKED.
     */
    @Transactional
    public void closeForAreaDeactivation(List<UUID> visitIds, User admin, String reason, OffsetDateTime now) {
        for (UUID id : visitIds) {
            GuestVisit visit = lockVisit(id);
            if (!now.isBefore(visit.getEndTime())) {
                continue;
            }
            if (visit.getStatus() == GuestVisitStatus.APPROVED) {
                applyRevoke(visit, admin, reason, now);
            } else if (visit.getStatus() == GuestVisitStatus.PENDING) {
                GuestVisitAuditSnapshot before = snapshot(visit);
                visit.setStatus(GuestVisitStatus.CANCELLED);
                visit.setCancelledAt(now);
                visit.setCancelReason(reason);
                bumpVersion(visit);
                guestVisitRepository.save(visit);
                auditService.record(AuditTargetType.GUEST_VISIT, AuditAction.CANCEL, visit.getId().toString(), null, null,
                        before, snapshot(visit), reason, AuditActor.user(admin));
                // H2: báo host sau commit
                User host = visit.getHost();
                notifyAfterCommit(visit.getId(), NotificationType.GUEST_VISIT_CANCELLED_BY_SYSTEM, "Lượt khách bị hệ thống huỷ",
                        "Lượt khách vào " + areaNames(visit) + " (" + InAppNotificationService.formatTimeRange(visit.getStartTime(), visit.getEndTime())
                                + ") đã bị hệ thống huỷ. Lý do: " + reason + ".", () -> List.of(host));
            } else {
                log.info("Guest visit {} changed concurrently ({}), skip on area deactivation", id, visit.getStatus());
            }
        }
    }

    // ================================================================== đổi loại khu vực (fix/type-change-guest-visits)

    /**
     * ADMIN đổi loại khu vực: lượt khách chưa kết thúc (PENDING / APPROVED) chứa khu vực không còn hợp lệ khi loại mới
     * không nhận khách — cùng điều kiện BR-GV-04 với GuestRules#areaTypeAllowed (cổng ra vào từ chối AREA_TYPE_NOT_ALLOWED).
     * Loại mới vẫn nhận khách thì trả danh sách rỗng (giữ nguyên lượt khách). Chỉ đọc; dùng cho cả xem trước và thực thi.
     */
    public List<GuestVisit> findVisitsInvalidatedByTypeChange(UUID areaId, AreaLevel newLevel, OffsetDateTime now) {
        if (areaId == null || newLevel == null || GuestRules.GUEST_AREA_LEVELS.contains(newLevel)) {
            return List.of();
        }
        return guestVisitRepository.findNotEndedByArea(
                areaId, List.of(GuestVisitStatus.PENDING, GuestVisitStatus.APPROVED), now);
    }

    /** BR-GV-11: trả câu mô tả vế đầu tiên không còn thoả, hoặc null. */
    String recheckForApproval(GuestVisit visit, OffsetDateTime now) {
        User host = visit.getHost();
        if (!rules.hostEligible(host)) {
            return "người mời không còn đủ điều kiện (tài khoản đang hoạt động, cấp truy cập từ " + rules.hostMinLevel() + " trở lên)";
        }
        try {
            validateWindow(visit.getStartTime(), visit.getEndTime(), now, false);
        } catch (GuestException ex) {
            return ex.getMessage();
        }
        for (Area area : visit.getAreas()) {
            if (!rules.areaActive(area)) {
                return "khu vực " + area.getName() + " đã ngừng hoạt động";
            }
            if (!rules.areaTypeAllowed(area)) {
                return "khu vực " + area.getName() + " không còn thuộc loại nhận khách";
            }
            if (!rules.hostCoversArea(host, area, visit.getStartTime(), visit.getEndTime())) {
                return "người mời không còn quyền vào khu vực " + area.getName() + " trong suốt khung giờ";
            }
        }
        return null;
    }

    // ================================================================== dùng chung

    User currentUser(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
    }

    GuestVisit lockVisit(UUID id) {
        return guestVisitRepository.findByIdForUpdate(id).orElseThrow(() -> new GuestException(GuestErrorCode.ERR_GUEST_001));
    }

    static void checkVersion(GuestVisit visit, Long clientVersion) {
        if (!Objects.equals(visit.getVersion(), clientVersion)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_016);
        }
    }

    static void bumpVersion(GuestVisit visit) {
        visit.setVersion((visit.getVersion() != null ? visit.getVersion() : 0L) + 1);
    }

    /** Lý do tuỳ chọn: rỗng = không gửi; có thì REASON_MIN_LENGTH..REASON_MAX_LENGTH sau trim (checklist 6.1). */
    String optionalReason(String raw) {
        String t = raw != null ? raw.trim() : "";
        if (t.isEmpty()) {
            return null;
        }
        if (!reasonValidator.isValid(t)) {
            throw reasonError();
        }
        return t;
    }

    /** Lý do bắt buộc REASON_MIN_LENGTH..REASON_MAX_LENGTH sau trim. */
    String requiredReason(String raw) {
        String t = optionalReason(raw);
        if (t == null) {
            throw reasonError();
        }
        return t;
    }

    /** ERR_GUEST_019 ghi giới hạn độ dài lý do đang cấu hình. */
    private GuestException reasonError() {
        ReasonLimits limits = reasonValidator.limits();
        return new GuestException(GuestErrorCode.ERR_GUEST_019, limits.minLength(), limits.maxLength());
    }

    void deleteAllBiometrics(GuestVisit visit, OffsetDateTime now, AuditActor actor, String reason) {
        for (Guest g : visit.getGuests()) {
            biometricService.deleteBiometrics(g, now, actor, reason);
        }
    }

    static GuestVisitAuditSnapshot snapshot(GuestVisit v) {
        return new GuestVisitAuditSnapshot(v.getStatus(), v.getVersion(),
                v.getHost() != null ? v.getHost().getUserCode() : null,
                v.getStartTime(), v.getEndTime(),
                v.getAreas().stream().map(Area::getId).toList(),
                v.getGuests().size());
    }

    static String areaNames(GuestVisit v) {
        return String.join(", ", v.getAreas().stream().map(Area::getName).toList());
    }

    /** Gửi thông báo sau commit (không làm rollback nghiệp vụ), chống trùng theo (lượt, loại). */
    void notifyAfterCommit(UUID visitId, NotificationType type, String title, String message,
                           java.util.function.Supplier<List<User>> recipients) {
        Runnable task = () -> {
            InAppNotificationService service = notificationServiceProvider.getIfAvailable();
            if (service == null) {
                return;
            }
            try {
                if (notificationRepository.existsByReferenceIdAndType(visitId, type)) {
                    return;
                }
                service.createForUsers(recipients.get(), type, title, message, visitId, REF_TYPE_GUEST_VISIT);
            } catch (Exception ex) {
                log.error("Failed to send {} for guest visit {}: {}", type, visitId, ex.getMessage(), ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    GuestVisitResponse toResponse(GuestVisit v) {
        List<GuestVisitAreaResponse> areas = v.getAreas().stream()
                .map(a -> new GuestVisitAreaResponse(a.getId(), a.getName(), a.getAreaLevel()))
                .toList();
        List<GuestResponse> guests = v.getGuests().stream()
                .map(g -> new GuestResponse(g.getId(), g.getFullName(), g.getOrganization(), g.getBiometricStatus(),
                        g.getPhotoAttachedAt(), g.getConsentNoticeVersion(), g.getAnonymizedAt() != null))
                .toList();
        return new GuestVisitResponse(v.getId(),
                v.getHost().getUserCode(), v.getHost().getFullName(),
                v.getPurpose(), v.getStartTime(), v.getEndTime(), v.getStatus(), v.getVersion(),
                areas, guests,
                v.getReviewedBy() != null ? v.getReviewedBy().getFullName() : null, v.getReviewedAt(), v.getReviewReason(),
                v.getRevokedBy() != null ? v.getRevokedBy().getFullName() : null, v.getRevokedAt(), v.getRevokeReason(),
                v.getCancelledAt(), v.getCancelReason(), v.getClosedAt(), v.getCreatedAt());
    }
}
