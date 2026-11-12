package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUserInfo;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.AssignedPersonnelErrorCode;
import com.fa26se040.icss.exception.AssignedPersonnelException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Quản lý Assigned Personnel: FM gán người dùng vào khu vực hạn chế.
 * Mọi business rule BR-AP-xx được chặn tại đây (backend là nơi chặn thật).
 * BR-AP-01 (phân quyền) nằm ở AreaAssignedPersonnelController qua @PreAuthorize.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AreaAssignedPersonnelService {

    /** BR-AP-24: lý do thu hồi AP khi tài khoản người được gán bị vô hiệu hoá. */
    public static final String REVOKE_REASON_USER_DEACTIVATED = "Tài khoản người được gán đã bị vô hiệu hoá";
    /** BR-AP-25: lý do thu hồi AP khi tài khoản người được gán bị xoá (lẻ hoặc theo lô import). */
    public static final String REVOKE_REASON_USER_DELETED = "Tài khoản người được gán đã bị xoá";

    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SystemConfigService systemConfigService;

    @Transactional(readOnly = true)
    public List<AssignedPersonnelResponse> getByArea(UUID areaId, AssignedPersonnelStatus status) {
        // Cho phép xem lịch sử cả khi khu vực đã ngừng hoạt động
        areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        OffsetDateTime now = OffsetDateTime.now();
        return assignedPersonnelRepository.findByAreaIdWithDetails(areaId).stream()
                .map(a -> mapToResponse(a, now))
                .filter(r -> status == null || r.status() == status)
                .toList();
    }

    @Transactional
    public AssignedPersonnelResponse create(UUID areaId, AssignedPersonnelCreateRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        // BR-AP-05: không gán vào area inactive hoặc đã bị xoá mềm
        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_007);
        }

        if (area.getAreaLevel() == AreaLevel.PUBLIC) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_010);
        }

        // BR-AP-06: người thao tác không tự gán quyền chỉ định cho chính mình
        if (actor.getId().equals(request.userId())) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_011);
        }

        // Khoá dòng users để tuần tự hoá các thao tác gán cùng user (chống race BR-AP-03)
        User user = assignedPersonnelRepository.findUserByIdForUpdate(request.userId())
                .orElseThrow(() -> new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_005));

        // BR-AP-04: không gán user inactive hoặc đã bị xoá mềm
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getDeletedAt() != null) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_006);
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime validFrom = request.validFrom() != null ? request.validFrom() : now;
        OffsetDateTime validTo = request.validTo();
        boolean untilClosingTime = Boolean.TRUE.equals(request.untilClosingTime());
        if (untilClosingTime && validTo != null) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_015);
        }

        validateValidTo(validFrom, validTo, now);
        // BR-AP-28: hiệu lực từ giờ FM chọn; giờ đó đã qua thì tính từ lúc cấp, không có hiệu lực ngược
        // (mỗi đợt cấp là một bản ghi, E-3); bỏ trống = now. validTo > now nên vẫn > validFrom sau khi lấy max
        if (validFrom.isBefore(now)) {
            validFrom = now;
        }
        // Ô "Hết giờ hành chính" (Lucas 05/11): hết hạn lúc đóng cửa CỦA NGÀY bắt đầu (giờ Việt Nam);
        // giờ đóng cửa = AI_AFTER_HOUR_START. Bắt đầu đã qua giờ đó -> ERR_AP_014, không tạo bản ghi
        if (untilClosingTime) {
            validTo = closingTimeOfDay(validFrom);
            if (!validTo.isAfter(validFrom)) {
                throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_014);
            }
        }
        validateNoOverlap(area.getId(), user.getId(), validFrom, validTo, null);

        AreaAssignedPersonnel entity = AreaAssignedPersonnel.builder()
                .area(area)
                .user(user)
                .validFrom(validFrom)
                .validTo(validTo)
                .note(normalizeNote(request.note()))
                .createdBy(actor)
                .createdAt(now)
                .updatedAt(now)
                .build();

        AreaAssignedPersonnel saved = assignedPersonnelRepository.save(entity);
        log.info("Assigned user {} to area {} (record {}) by {}", user.getId(), area.getId(), saved.getId(), actorEmail);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(
                        saved.getValidFrom(),
                        saved.getValidTo(),
                        computeStatus(saved, now)
                );
        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_ASSIGNMENT,
                com.fa26se040.icss.enums.AuditAction.ASSIGN,
                saved.getId().toString(),
                area,
                user,
                null,
                newSnapshot,
                request.reason(),
                actor
        );

        return mapToResponse(saved, now);
    }

    @Transactional
    public AssignedPersonnelResponse revoke(UUID areaId, UUID id, AssignedPersonnelRevokeRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);

        AreaAssignedPersonnel entity = getOwnedRecord(areaId, id);

        // BR-AP-08: lý do thu hồi bắt buộc, không rỗng
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.isEmpty()) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_008);
        }

        // BR-AP-08: thu hồi bản ghi đã thu hồi → lỗi
        if (entity.getRevokedAt() != null) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_009);
        }

        OffsetDateTime now = OffsetDateTime.now();
        AssignedPersonnelStatus oldStatus = computeStatus(entity, now);

        // BR-AP-30: chỉ thu hồi AP ACTIVE / UPCOMING; AP đã hết hạn thì không còn gì để thu hồi
        if (oldStatus == AssignedPersonnelStatus.EXPIRED) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_012);
        }

        // BR-AP-08: thu hồi có hiệu lực ngay.
        // BE-04: UPDATE có điều kiện (revoked_at IS NULL); 0 dòng = người khác vừa thu hồi -> ERR_AP_009, không audit
        if (assignedPersonnelRepository.revokeIfNotRevoked(entity.getId(), now, actor, reason) == 0) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_009);
        }
        // Đồng bộ entity đang quản lý với dòng vừa ghi (cùng giá trị, chỉ để trả response / audit)
        entity.setRevokedAt(now);
        entity.setRevokedBy(actor);
        entity.setRevokeReason(reason);
        entity.setUpdatedAt(now);

        AreaAssignedPersonnel saved = entity;
        log.info("Revoked assigned personnel {} by {}", saved.getId(), actorEmail);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(
                        entity.getValidFrom(),
                        entity.getValidTo(),
                        oldStatus
                );
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(
                        saved.getValidFrom(),
                        saved.getValidTo(),
                        AssignedPersonnelStatus.REVOKED
                );
        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_ASSIGNMENT,
                com.fa26se040.icss.enums.AuditAction.REVOKE,
                saved.getId().toString(),
                saved.getArea(),
                saved.getUser(),
                oldSnapshot,
                newSnapshot,
                reason,
                actor
        );

        return mapToResponse(saved, now);
    }

    /**
     * BR-AP-24/25/26: thu hồi mọi AP ACTIVE + UPCOMING của một user khi tài khoản bị vô hiệu hoá hoặc xoá.
     * revoked_by = ADMIN đang thao tác, audit từng AP (AREA_ASSIGNMENT / REVOKE) theo correlation của thao tác.
     * KHÔNG gửi thông báo (tài khoản không còn đăng nhập được). Chạy trong transaction của thao tác gọi, caller
     * phải khoá dòng users trước (BR-AP-27). Kích hoạt lại / khôi phục tài khoản không trả lại AP.
     *
     * @return số AP đã thu hồi
     */
    @Transactional
    public int revokeAllOfUser(UUID userId, User actor, String reason, OffsetDateTime now) {
        java.util.Objects.requireNonNull(actor, "actor");
        List<AreaAssignedPersonnel> aps = assignedPersonnelRepository.findNotRevokedNotExpiredByUser(userId, now);
        for (AreaAssignedPersonnel ap : aps) {
            AssignedPersonnelStatus oldStatus = computeStatus(ap, now);
            ap.setRevokedAt(now);
            ap.setRevokedBy(actor);
            ap.setRevokeReason(reason);
            ap.setUpdatedAt(now);
            assignedPersonnelRepository.save(ap);
            auditService.record(
                    com.fa26se040.icss.enums.AuditTargetType.AREA_ASSIGNMENT,
                    com.fa26se040.icss.enums.AuditAction.REVOKE,
                    ap.getId().toString(),
                    ap.getArea(),
                    ap.getUser(),
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(ap.getValidFrom(), ap.getValidTo(), oldStatus),
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(ap.getValidFrom(), ap.getValidTo(),
                            AssignedPersonnelStatus.REVOKED),
                    reason,
                    actor
            );
        }
        if (!aps.isEmpty()) {
            log.info("Revoked {} assigned personnel records of user {} by {}: {}", aps.size(), userId, actor.getEmail(), reason);
        }
        return aps.size();
    }

    /**
     * Giờ đóng cửa của ngày (giờ Asia/Ho_Chi_Minh) chứa thời điểm at = ngày đó + AI_AFTER_HOUR_START (giờ bắt đầu khung
     * ngoài giờ, coi là giờ đóng cửa). Đọc cấu hình mỗi lần gọi; sai định dạng HH:mm thì dùng mặc định của ConfigKey
     * (cùng cách với AfterHoursWindow).
     */
    OffsetDateTime closingTimeOfDay(OffsetDateTime at) {
        java.time.ZoneId vnZone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        String raw = systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START);
        java.time.LocalTime closing;
        try {
            closing = java.time.LocalTime.parse(raw != null ? raw.trim() : ConfigKey.AI_AFTER_HOUR_START.getDefaultValue());
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("Giá trị [{}] của cấu hình AI_AFTER_HOUR_START không đúng HH:mm. Dùng mặc định: {}",
                    raw, ConfigKey.AI_AFTER_HOUR_START.getDefaultValue());
            closing = java.time.LocalTime.parse(ConfigKey.AI_AFTER_HOUR_START.getDefaultValue());
        }
        return at.atZoneSameInstant(vnZone).toLocalDate().atTime(closing).atZone(vnZone).toOffsetDateTime();
    }

    /**
     * Trạng thái tính tại thời điểm :now, không lưu DB.
     * Khoảng hiệu lực là nửa mở [validFrom, validTo).
     */
    static AssignedPersonnelStatus computeStatus(AreaAssignedPersonnel a, OffsetDateTime now) {
        if (a.getRevokedAt() != null) {
            return AssignedPersonnelStatus.REVOKED;
        }
        if (now.isBefore(a.getValidFrom())) {
            return AssignedPersonnelStatus.UPCOMING;
        }
        if (a.getValidTo() != null && !now.isBefore(a.getValidTo())) {
            return AssignedPersonnelStatus.EXPIRED;
        }
        return AssignedPersonnelStatus.ACTIVE;
    }

    private void validateValidTo(OffsetDateTime validFrom, OffsetDateTime validTo, OffsetDateTime now) {
        if (validTo == null) {
            return;
        }
        // BR-AP-02: validTo > validFrom
        if (!validTo.isAfter(validFrom)) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_002);
        }
        // BR-AP-02: validTo > now (muốn kết thúc ngay thì dùng thu hồi)
        if (!validTo.isAfter(now)) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_003);
        }
    }

    private void validateNoOverlap(UUID areaId, UUID userId, OffsetDateTime validFrom, OffsetDateTime validTo, UUID excludeId) {
        // BR-AP-03: cùng area + user không có hai bản ghi chưa thu hồi chồng lấn [validFrom, validTo), NULL = vô hạn
        List<AreaAssignedPersonnel> overlapping = validTo == null
                ? assignedPersonnelRepository.findOverlappingNotRevokedOpenEnded(areaId, userId, validFrom)
                : assignedPersonnelRepository.findOverlappingNotRevoked(areaId, userId, validFrom, validTo);

        boolean conflict = overlapping.stream()
                .anyMatch(a -> excludeId == null || !excludeId.equals(a.getId()));
        if (conflict) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_004);
        }
    }

    private AreaAssignedPersonnel getOwnedRecord(UUID areaId, UUID id) {
        // {id} phải thuộc đúng {areaId}, sai → 404
        return assignedPersonnelRepository.findByIdAndAreaIdWithDetails(id, areaId)
                .orElseThrow(() -> new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_001));
    }

    private User resolveActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AssignedPersonnelResponse mapToResponse(AreaAssignedPersonnel a, OffsetDateTime now) {
        User u = a.getUser();
        AssignedPersonnelUserInfo userInfo = u == null ? null : new AssignedPersonnelUserInfo(
                u.getId(),
                u.getUserCode(),
                u.getFullName(),
                u.getRole()
        );

        return new AssignedPersonnelResponse(
                a.getId(),
                a.getArea() != null ? a.getArea().getId() : null,
                userInfo,
                a.getValidFrom(),
                a.getValidTo(),
                a.getNote(),
                computeStatus(a, now),
                a.getCreatedBy() != null ? a.getCreatedBy().getFullName() : null,
                a.getCreatedAt(),
                a.getRevokedAt(),
                a.getRevokedBy() != null ? a.getRevokedBy().getFullName() : null,
                a.getRevokeReason()
        );
    }
}
