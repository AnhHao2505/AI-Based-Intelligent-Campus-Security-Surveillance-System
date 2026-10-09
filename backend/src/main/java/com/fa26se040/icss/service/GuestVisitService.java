package com.fa26se040.icss.service;

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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Lượt khách: host tạo / xem / huỷ (BR-GV-01..06, 09); FM duyệt / từ chối / thu hồi (BR-GV-10..13).
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
    private final GuestRules rules;
    private final GuestBiometricService biometricService;
    private final AuditService auditService;
    private final ObjectProvider<InAppNotificationService> notificationServiceProvider;

    // ================================================================== tạo (BR-GV-01..06)

    @Transactional
    public GuestVisitResponse create(GuestVisitCreateRequest req, String actorEmail) {
        User host = currentUser(actorEmail);
        OffsetDateTime now = OffsetDateTime.now();

        // BR-GV-01
        if (!rules.hostEligible(host)) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_002, rules.hostMinLevel());
        }
        // BR-GV-02
        List<Guest> guests = buildGuests(req.guests());
        // BR-GV-03
        String purpose = req.purpose() != null ? req.purpose().trim() : "";
        if (purpose.length() < 10 || purpose.length() > 500) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_006);
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

        auditService.record(AuditTargetType.GUEST_VISIT, AuditAction.CREATE, saved.getId().toString(), null, null,
                null, snapshot(saved), null, AuditActor.user(host));

        // BR-GV-33: mọi FM đang hoạt động
        String message = host.getFullName() + " (" + host.getUserCode() + ") mời " + guests.size() + " khách vào "
                + areaNames(saved) + " (" + InAppNotificationService.formatTimeRange(saved.getStartTime(), saved.getEndTime()) + ").";
        notifyAfterCommit(saved.getId(), NotificationType.GUEST_VISIT_PENDING, "Lượt khách mới chờ duyệt", message,
                () -> userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER));
        return toResponse(saved);
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
     * loại INTERNAL / CONTACT). Không lọc theo cờ explicit nên có cả khu vực INTERNAL.
     * BR-GV-06 (host phủ khu vực) phụ thuộc khung giờ nên vẫn kiểm lúc tạo lượt (ERR_GUEST_014).
     */
    @Transactional(readOnly = true)
    public List<com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse> listSelectableAreas() {
        return areaRepository.findAvailableForRequest(GuestRules.GUEST_AREA_LEVELS).stream()
                .filter(a -> rules.areaActive(a) && rules.areaTypeAllowed(a))
                .map(a -> new com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse(
                        a.getId(), a.getName(), a.getAreaLevel(), a.getBuilding(), a.getFloor()))
                .toList();
    }

    /** B-04: điều kiện mời khách của người gọi — cùng GuestRules.hostEligible mà bước tạo lượt dùng (ERR_GUEST_002). */
    @Transactional(readOnly = true)
    public com.fa26se040.icss.dto.guest.GuestHostEligibilityResponse eligibility(String actorEmail) {
        User me = currentUser(actorEmail);
        return new com.fa26se040.icss.dto.guest.GuestHostEligibilityResponse(
                rules.hostEligible(me), me.getAccessLevel(), rules.hostMinLevel());
    }

    // ================================================================== xem

    @Transactional(readOnly = true)
    public Page<GuestVisitResponse> listMine(String actorEmail, Pageable pageable) {
        User host = currentUser(actorEmail);
        return guestVisitRepository.findByHostIdOrderByCreatedAtDesc(host.getId(), pageable).map(this::toResponse);
    }

    /** Host xem lượt của mình; FM và ADMIN xem mọi lượt (duyệt / gắn ảnh). Người khác -> 404 (không lộ lượt tồn tại). */
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
        // BR-GV-09, 27: huỷ lượt đã duyệt -> xoá sinh trắc ngay
        if (from == GuestVisitStatus.APPROVED) {
            deleteAllBiometrics(visit, now, actor, "Lượt khách bị huỷ");
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

        User host = visit.getHost();
        String range = InAppNotificationService.formatTimeRange(visit.getStartTime(), visit.getEndTime());
        if (decision == GuestVisitStatus.APPROVED) {
            notifyAfterCommit(visit.getId(), NotificationType.GUEST_VISIT_APPROVED, "Lượt khách đã được duyệt",
                    "Lượt khách vào " + areaNames(visit) + " (" + range + ") đã được duyệt.", () -> List.of(host));
            notifyAfterCommit(visit.getId(), NotificationType.GUEST_PHOTO_REQUIRED, "Cần gắn ảnh khách",
                    "Lượt khách của " + host.getFullName() + " vào " + areaNames(visit) + " (" + range + ") đã được duyệt, "
                            + visit.getGuests().size() + " khách cần gắn ảnh sau khi đồng ý tại quầy.",
                    () -> userRepository.findActiveUsersByRole(Role.ADMIN));
        } else {
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
     * Xoá sinh trắc qua deleteAllBiometrics (A9: object MinIO xoá sau commit), báo host sau commit.
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
        // BR-GV-13, 27: thu hồi -> xoá sinh trắc ngay
        deleteAllBiometrics(visit, now, actor, "Lượt khách bị thu hồi");
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

    /** Lý do tuỳ chọn: rỗng = không gửi; có thì 10–500 sau trim. */
    static String optionalReason(String raw) {
        String t = raw != null ? raw.trim() : "";
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() < 10 || t.length() > 500) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_019);
        }
        return t;
    }

    /** Lý do bắt buộc 10–500 sau trim. */
    static String requiredReason(String raw) {
        String t = optionalReason(raw);
        if (t == null) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_019);
        }
        return t;
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
