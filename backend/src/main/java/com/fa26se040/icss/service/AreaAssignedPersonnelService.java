package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUpdateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUserInfo;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
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

    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final AccessControlAuditService auditService;

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

        validateValidTo(validFrom, validTo, now);
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
                com.fa26se040.icss.enums.AccessControlTargetType.AREA_ASSIGNMENT,
                com.fa26se040.icss.enums.AccessControlAction.ASSIGN,
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
    public AssignedPersonnelResponse updateValidTo(UUID areaId, UUID id, AssignedPersonnelUpdateRequest request, String actorEmail) {
        User actor = resolveActor(actorEmail);

        AreaAssignedPersonnel entity = getOwnedRecord(areaId, id);

        // BR-AP-08: không sửa bản ghi đã thu hồi
        if (entity.getRevokedAt() != null) {
            throw new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_009);
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newValidTo = request != null ? request.validTo() : null;
        OffsetDateTime oldValidTo = entity.getValidTo();

        // BR-AL-06: Thao tác không làm thay đổi giá trị (new == old) -> không ghi log, trả về hiện tại
        if (java.util.Objects.equals(oldValidTo, newValidTo)) {
            log.info("validTo of assigned personnel {} unchanged, skipping audit log", entity.getId());
            return mapToResponse(entity, now);
        }

        // Khoá dòng users để tuần tự hoá các thao tác gán cùng user (chống race BR-AP-03)
        assignedPersonnelRepository.findUserByIdForUpdate(entity.getUser().getId());

        validateValidTo(entity.getValidFrom(), newValidTo, now);
        validateNoOverlap(entity.getArea().getId(), entity.getUser().getId(), entity.getValidFrom(), newValidTo, entity.getId());

        AssignedPersonnelStatus oldStatus = computeStatus(entity, now);

        entity.setValidTo(newValidTo);
        entity.setUpdatedAt(now);

        AreaAssignedPersonnel saved = assignedPersonnelRepository.save(entity);
        log.info("Updated validTo of assigned personnel {} to {} by {}", saved.getId(), newValidTo, actorEmail);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(
                        entity.getValidFrom(),
                        oldValidTo,
                        oldStatus
                );
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot(
                        saved.getValidFrom(),
                        newValidTo,
                        computeStatus(saved, now)
                );
        auditService.record(
                com.fa26se040.icss.enums.AccessControlTargetType.AREA_ASSIGNMENT,
                com.fa26se040.icss.enums.AccessControlAction.UPDATE_VALIDITY,
                saved.getId().toString(),
                saved.getArea(),
                saved.getUser(),
                oldSnapshot,
                newSnapshot,
                request != null ? request.reason() : null,
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

        // BR-AP-08: thu hồi có hiệu lực ngay
        entity.setRevokedAt(now);
        entity.setRevokedBy(actor);
        entity.setRevokeReason(reason);
        entity.setUpdatedAt(now);

        AreaAssignedPersonnel saved = assignedPersonnelRepository.save(entity);
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
                com.fa26se040.icss.enums.AccessControlTargetType.AREA_ASSIGNMENT,
                com.fa26se040.icss.enums.AccessControlAction.REVOKE,
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
