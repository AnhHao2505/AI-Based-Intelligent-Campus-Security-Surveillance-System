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
