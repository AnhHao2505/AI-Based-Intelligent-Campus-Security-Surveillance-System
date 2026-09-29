package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.dto.area.AreaGeometryResponse;
import com.fa26se040.icss.dto.area.AreaListItemResponse;
import com.fa26se040.icss.dto.area.AreaMapPinResponse;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaCamerasSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaGeometrySnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaSnapshot;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.dto.area.AreaCameraResponse;
import com.fa26se040.icss.dto.camera.CameraSimpleResponse;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaRepository areaRepository;
    private final CameraRepository cameraRepository;
    private final UserRepository userRepository;
    private final AreaLevelPresetRepository areaLevelPresetRepository;
    private final com.fa26se040.icss.repository.FloorRepository floorRepository;
    private final AreaValidator areaValidator;
    private final AreaDependencyChecker dependencyChecker;
    private final AreaGeometryValidator geometryValidator;
    private final AuditService auditService;
    private final com.fa26se040.icss.repository.ReasonCatalogRepository reasonCatalogRepository;
    private final com.fa26se040.icss.repository.AreaEventSessionRepository eventSessionRepository;
    private final com.fa26se040.icss.repository.AreaEventScheduleRepository eventScheduleRepository;
    private final SystemConfigService systemConfigService;
    private final org.springframework.beans.factory.ObjectProvider<InAppNotificationService> inAppNotificationServiceProvider;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    // Step 5b: đổi loại khu vực cần đánh giá đơn truy cập và AP của khu vực
    private final com.fa26se040.icss.repository.AccessRequestRepository accessRequestRepository;
    private final com.fa26se040.icss.repository.AreaAssignedPersonnelRepository assignedPersonnelRepository;

    /** Nguồn audit khi hệ thống huỷ đơn do ADMIN đổi loại khu vực (BR-TC-08). */
    public static final String AREA_TYPE_CHANGE_SOURCE = "AREA_TYPE_CHANGE";
    /** Lý do huỷ đơn cố định khi khu vực chuyển sang công khai (BR-TC-14). */
    public static final String PUBLIC_CANCEL_REASON = "Khu vực đã chuyển sang công khai, không cần đơn";

    private java.util.Map<AreaLevel, AreaLevelPreset> loadPresetMap() {
        return areaLevelPresetRepository.findAll().stream()
                .collect(Collectors.toMap(AreaLevelPreset::getAreaLevel, java.util.function.Function.identity(), (a, b) -> a));
    }

    private boolean computeDiffersFromPreset(Area area, java.util.Map<AreaLevel, AreaLevelPreset> presetMap) {
        if (area == null || area.getAreaLevel() == null) {
            return false;
        }
        AreaLevelPreset preset = presetMap.get(area.getAreaLevel());
        if (preset == null) {
            return false;
        }
        return !java.util.Objects.equals(area.getAreaAccessLevel(), preset.getAreaAccessLevel())
                || !java.util.Objects.equals(area.getExplicitAuthorizationRequired(), preset.getExplicitAuthorizationRequired());
    }

    // ===================================================================== Step 5b — version khu vực (BR-TC-13, TC-17)

    /**
     * Ảnh chụp các cột nghiệp vụ của areas (không gồm updated_at, version) lúc bắt đầu thao tác,
     * để biết thao tác có đổi dữ liệu thật hay không.
     */
    private record AreaRowState(long version, List<Object> values) {
        static AreaRowState of(Area a) {
            return new AreaRowState(
                    a.getVersion() != null ? a.getVersion() : 0L,
                    java.util.Arrays.asList(
                            a.getName(),
                            a.getAreaLevel(),
                            a.getAreaAccessLevel(),
                            a.getExplicitAuthorizationRequired(),
                            a.getOpenToMembers(),
                            a.getOpenUntil() != null ? a.getOpenUntil().toInstant() : null,
                            a.getFloorEntity() != null ? a.getFloorEntity().getId() : null,
                            a.getBuilding(),
                            a.getFloor(),
                            a.getGeometry(),
                            a.getCenterLatitude(),
                            a.getCenterLongitude(),
                            a.getIsActive(),
                            a.getDeletedAt() != null ? a.getDeletedAt().toInstant() : null
                    ));
        }
    }

    /** Thiếu version -> 400 ERR_AREA_044 (kiểm cùng nhóm dữ liệu, trước khi khoá). */
    private static void requireVersion(Long version) {
        if (version == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_044);
        }
    }

    /** So version client gửi với dòng areas đã khoá -> lệch thì 409 ERR_AREA_045. */
    private static void checkVersion(Area area, Long clientVersion) {
        if (!java.util.Objects.equals(area.getVersion(), clientVersion)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_045);
        }
    }

    /**
     * TC-17: dòng areas đổi thật so với lúc bắt đầu thao tác -> version = version lúc đầu + 1 (gọi nhiều lần vẫn chỉ +1);
     * lưu không đổi dữ liệu thì giữ nguyên version.
     */
    private static void bumpVersionIfChanged(Area area, AreaRowState before) {
        if (!AreaRowState.of(area).values().equals(before.values())) {
            area.setVersion(before.version() + 1);
        }
    }

    /** BR-TC-02, TC-12: lý do 10–500 ký tự sau trim; rỗng coi như không gửi. Sai -> 400 ERR_AREA_050. */
    private static String normalizeReason(String raw, boolean required) {
        String trimmed = raw != null ? raw.trim() : "";
        if (trimmed.isEmpty()) {
            if (required) {
                throw new AreaException(AreaErrorCode.ERR_AREA_050);
            }
            return null;
        }
        if (trimmed.length() < 10 || trimmed.length() > 500) {
            throw new AreaException(AreaErrorCode.ERR_AREA_050);
        }
        return trimmed;
    }

    // ===================================================================== Step 5b — đổi loại khu vực (BR-TC-03..16)

    /** Tên loại khu vực hiển thị trong thông báo (BR-TC-10). */
    private static String areaLevelLabel(AreaLevel level) {
        if (level == null) {
            return "—";
        }
        return switch (level) {
            case PUBLIC -> "Công khai";
            case INTERNAL_CONFIDENTIAL -> "Bảo mật nội bộ";
            case CONFIDENTIAL_CONTACT_REQUIRED -> "Liên hệ trước";
            case HIGHLY_CONFIDENTIAL -> "Tuyệt mật";
        };
    }

    private static boolean eventModeAllowed(AreaLevel level) {
        return level == AreaLevel.INTERNAL_CONFIDENTIAL || level == AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED;
    }

    /** Một đơn sẽ bị hệ thống huỷ kèm lý do ghi vào cancel_reason. */
    private record CancelCandidate(com.fa26se040.icss.entity.AccessRequest request, String reason) {
    }

    /**
     * Kết quả đánh giá đổi loại — DÙNG CHUNG cho xem trước (BR-TC-03) và thực thi PUT (đánh giá lại sau khi khoá).
     * blockers giữ đúng thứ tự ưu tiên: sự kiện đang mở (048) -> lịch chờ (042) -> AP hiệu lực (049).
     */
    private record TypeChangeEvaluation(
            AreaLevel currentLevel,
            AreaLevel newLevel,
            int newAccessLevel,
            boolean newExplicitAuthorizationRequired,
            int activeApCount,
            List<CancelCandidate> approvedToCancel,
            List<CancelCandidate> pendingToCancel,
            int pendingNotApprovableCount,
            boolean eventActive,
            int pendingScheduleCount,
            List<AreaException> blockers
    ) {
        List<CancelCandidate> allToCancel() {
            List<CancelCandidate> all = new ArrayList<>(approvedToCancel);
            all.addAll(pendingToCancel);
            return all;
        }
    }

    private TypeChangeEvaluation evaluateTypeChange(Area area, AreaLevel newLevel, OffsetDateTime now) {
        AreaLevel currentLevel = area.getAreaLevel();

        // TC-04: preset của loại mới; thiếu preset -> 3/true (fail-closed)
        AreaLevelPreset preset = areaLevelPresetRepository.findById(newLevel).orElse(null);
        int newAccessLevel = preset != null && preset.getAreaAccessLevel() != null ? preset.getAreaAccessLevel() : 3;
        boolean newExplicit = preset == null || preset.getExplicitAuthorizationRequired() == null
                || preset.getExplicitAuthorizationRequired();

        boolean eventActive = area.isEventActive(now);
        List<com.fa26se040.icss.entity.AreaEventSchedule> pendingSchedules = eventScheduleRepository != null
                ? eventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(area.getId(), com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                : List.of();
        int activeApCount = assignedPersonnelRepository != null
                ? (int) assignedPersonnelRepository.countNotRevokedNotExpired(area.getId(), now)
                : 0;

        List<AreaException> blockers = new ArrayList<>();
        if (eventActive && !eventModeAllowed(newLevel)) {
            blockers.add(new AreaException(AreaErrorCode.ERR_AREA_048, (Object) areaLevelLabel(newLevel)));
        }
        if (!pendingSchedules.isEmpty() && !eventModeAllowed(newLevel)) {
            blockers.add(new AreaException(AreaErrorCode.ERR_AREA_042, buildPendingSchedulesErrorMessage(pendingSchedules)));
        }
        if (newLevel == AreaLevel.PUBLIC && activeApCount > 0) {
            blockers.add(new AreaException(AreaErrorCode.ERR_AREA_049, activeApCount));
        }

        List<CancelCandidate> approvedToCancel = new ArrayList<>();
        List<CancelCandidate> pendingToCancel = new ArrayList<>();
        int pendingNotApprovable = 0;
        List<com.fa26se040.icss.entity.AccessRequest> requests = accessRequestRepository != null
                ? accessRequestRepository.findNotEndedByAreaWithParticipants(
                        area.getId(), List.of(com.fa26se040.icss.enums.RequestStatus.PENDING, com.fa26se040.icss.enums.RequestStatus.APPROVED), now)
                : List.of();
        // Chỉ đọc cấu hình khi thật sự có đơn cần đánh giá
        boolean groupAllowedInPrivate = !requests.isEmpty()
                && systemConfigService.getBoolean(com.fa26se040.icss.enums.ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE);
        for (com.fa26se040.icss.entity.AccessRequest r : requests) {
            boolean approved = r.getStatus() == com.fa26se040.icss.enums.RequestStatus.APPROVED;
            if (newLevel == AreaLevel.PUBLIC) {
                // TC-14: sang PUBLIC -> huỷ mọi PENDING + APPROVED chưa kết thúc
                (approved ? approvedToCancel : pendingToCancel).add(new CancelCandidate(r, PUBLIC_CANCEL_REASON));
                continue;
            }
            String violation = describeRuleViolation(r, newLevel, newAccessLevel, groupAllowedInPrivate);
            if (violation == null) {
                continue;
            }
            if (approved) {
                // TC-08: APPROVED chưa kết thúc không thoả quy tắc mới -> hệ thống huỷ
                approvedToCancel.add(new CancelCandidate(r, "Khu vực đổi loại từ " + areaLevelLabel(currentLevel)
                        + " sang " + areaLevelLabel(newLevel) + ": " + violation));
            } else {
                // TC-09: PENDING không huỷ, chỉ đếm số đơn sẽ không duyệt được
                pendingNotApprovable++;
            }
        }

        return new TypeChangeEvaluation(currentLevel, newLevel, newAccessLevel, newExplicit, activeApCount,
                approvedToCancel, pendingToCancel, pendingNotApprovable, eventActive, pendingSchedules.size(), blockers);
    }

    /** Cùng quy tắc FM kiểm khi duyệt đơn (BR-RQ-02); trả null nếu đơn vẫn thoả loại mới. */
    private static String describeRuleViolation(com.fa26se040.icss.entity.AccessRequest r, AreaLevel newLevel,
                                                int newAccessLevel, boolean groupAllowedInPrivate) {
        if (r.getRequestType() == com.fa26se040.icss.enums.RequestType.GROUP
                && newLevel == AreaLevel.HIGHLY_CONFIDENTIAL && !groupAllowedInPrivate) {
            return "khu vực Tuyệt mật không nhận đơn nhóm";
        }
        java.util.LinkedHashMap<UUID, User> participants = new java.util.LinkedHashMap<>();
        if (r.getRequester() != null) {
            participants.put(r.getRequester().getId(), r.getRequester());
        }
        if (r.getMembers() != null) {
            for (com.fa26se040.icss.entity.AccessRequestMember m : r.getMembers()) {
                if (m.getUser() != null) {
                    participants.putIfAbsent(m.getUser().getId(), m.getUser());
                }
            }
        }
        List<String> unqualified = new ArrayList<>();
        for (User u : participants.values()) {
            int level = u.getAccessLevel() != null ? u.getAccessLevel() : 1;
            if (level < newAccessLevel) {
                unqualified.add(u.getFullName() + " (" + u.getUserCode() + ")");
            }
        }
        if (unqualified.isEmpty()) {
            return null;
        }
        return "không đủ cấp truy cập " + newAccessLevel + ": " + String.join(", ", unqualified);
    }

    private static com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse.RequestItem toPreviewItem(CancelCandidate c) {
        com.fa26se040.icss.entity.AccessRequest r = c.request();
        return new com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse.RequestItem(
                r.getId(),
                r.getRequestType(),
                r.getRequester() != null ? r.getRequester().getUserCode() : null,
                r.getRequester() != null ? r.getRequester().getFullName() : null,
                r.getStartTime(),
                r.getEndTime()
        );
    }

    /** BR-TC-03: xem trước đổi loại (chỉ đọc). Cùng hàm đánh giá với PUT. */
    @Transactional(readOnly = true)
    public com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse previewTypeChange(UUID id, AreaLevel newLevel) {
        if (newLevel == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_003);
        }
        Area area = areaRepository.findById(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        TypeChangeEvaluation ev = evaluateTypeChange(area, newLevel, OffsetDateTime.now());
        return com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse.builder()
                .areaId(area.getId())
                .currentAreaLevel(area.getAreaLevel())
                .newAreaLevel(newLevel)
                .currentAreaAccessLevel(area.getAreaAccessLevel())
                .newAreaAccessLevel(ev.newAccessLevel())
                .currentExplicitAuthorizationRequired(area.getExplicitAuthorizationRequired())
                .newExplicitAuthorizationRequired(ev.newExplicitAuthorizationRequired())
                .activeAssignedPersonnelCount(ev.activeApCount())
                .approvedRequestsToCancel(ev.approvedToCancel().stream().map(AreaService::toPreviewItem).toList())
                .pendingRequestsNotApprovableCount(ev.pendingNotApprovableCount())
                .pendingRequestsToCancel(ev.pendingToCancel().stream().map(AreaService::toPreviewItem).toList())
                .eventActive(ev.eventActive())
                .pendingScheduleCount(ev.pendingScheduleCount())
                .blockingReasons(ev.blockers().stream()
                        .map(b -> new com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse.BlockingReason(
                                b.getErrorCode().getCode(), b.getMessage()))
                        .toList())
                .build();
    }

    /**
     * BR-TC-08, BR-TC-14: hệ thống huỷ đơn trong cùng transaction đổi loại. Mỗi đơn 1 audit (ACCESS_REQUEST / CANCEL),
     * actor SYSTEM AREA_TYPE_CHANGE, cùng correlation với thao tác ADMIN. Đơn vừa bị thao tác khác đổi trạng thái thì bỏ qua.
     */
    private List<com.fa26se040.icss.entity.AccessRequest> cancelRequestsBySystem(Area area, List<CancelCandidate> candidates, OffsetDateTime now) {
        List<com.fa26se040.icss.entity.AccessRequest> cancelled = new ArrayList<>();
        for (CancelCandidate c : candidates) {
            com.fa26se040.icss.entity.AccessRequest r = c.request();
            com.fa26se040.icss.dto.accesscontrol.snapshot.AccessRequestSnapshot before =
                    com.fa26se040.icss.dto.accesscontrol.snapshot.AccessRequestSnapshot.from(r);
            int updated = accessRequestRepository.cancelBySystemIfStatus(
                    r.getId(), r.getStatus(), com.fa26se040.icss.enums.RequestStatus.CANCELLED,
                    com.fa26se040.icss.enums.CancelSource.SYSTEM, c.reason(), now);
            if (updated != 1) {
                log.info("Access request {} changed concurrently, skip system cancel on area type change", r.getId());
                continue;
            }
            // Đồng bộ entity trong persistence context với dòng vừa UPDATE (dữ liệu y hệt, không đổi thêm gì)
            r.setStatus(com.fa26se040.icss.enums.RequestStatus.CANCELLED);
            r.setCancelSource(com.fa26se040.icss.enums.CancelSource.SYSTEM);
            r.setCancelledBy(null);
            r.setCancelReason(c.reason());
            r.setUpdatedAt(now);
            auditService.record(
                    AuditTargetType.ACCESS_REQUEST,
                    AuditAction.CANCEL,
                    r.getId().toString(),
                    area,
                    r.getRequester(),
                    before,
                    com.fa26se040.icss.dto.accesscontrol.snapshot.AccessRequestSnapshot.from(r),
                    c.reason(),
                    com.fa26se040.icss.dto.audit.AuditActor.system(AREA_TYPE_CHANGE_SOURCE)
            );
            cancelled.add(r);
        }
        return cancelled;
    }

    /** BR-TC-10, BR-TC-16: thông báo gửi SAU commit; lỗi thông báo không ảnh hưởng nghiệp vụ đã commit. */
    private void notifyAfterTypeChange(Area area, AreaLevel oldLevel, AreaLevel newLevel, String reason,
                                       List<com.fa26se040.icss.entity.AccessRequest> cancelled) {
        UUID areaId = area.getId();
        String areaName = area.getName();
        String fmMessage = "Khu vực " + areaName + ": " + areaLevelLabel(oldLevel) + " → " + areaLevelLabel(newLevel)
                + ". Lý do: " + reason + ". Số đơn bị huỷ: " + cancelled.size() + ".";

        // Chuẩn bị người nhận trong transaction (người gửi + thành viên, mỗi người 1 thông báo / đơn)
        record RequestNotice(UUID requestId, List<User> recipients, String message) {
        }
        List<RequestNotice> notices = new ArrayList<>();
        for (com.fa26se040.icss.entity.AccessRequest r : cancelled) {
            java.util.LinkedHashMap<UUID, User> recipients = new java.util.LinkedHashMap<>();
            if (r.getRequester() != null) {
                recipients.put(r.getRequester().getId(), r.getRequester());
            }
            if (r.getMembers() != null) {
                for (com.fa26se040.icss.entity.AccessRequestMember m : r.getMembers()) {
                    if (m.getUser() != null) {
                        recipients.putIfAbsent(m.getUser().getId(), m.getUser());
                    }
                }
            }
            String message = "Đơn truy cập khu vực " + areaName + " ("
                    + InAppNotificationService.formatTimeRange(r.getStartTime(), r.getEndTime())
                    + ") đã bị hệ thống huỷ. Lý do: " + r.getCancelReason() + ".";
            notices.add(new RequestNotice(r.getId(), new ArrayList<>(recipients.values()), message));
        }

        executeAfterCommitOrImmediately(() -> {
            InAppNotificationService notifService = inAppNotificationServiceProvider != null
                    ? inAppNotificationServiceProvider.getIfAvailable() : null;
            if (notifService == null) {
                return;
            }
            try {
                notifService.createForUsers(
                        userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.FACILITY_MANAGER),
                        com.fa26se040.icss.enums.NotificationType.AREA_TYPE_CHANGED,
                        "Khu vực đổi loại",
                        fmMessage,
                        areaId,
                        "AREA"
                );
            } catch (Exception ex) {
                log.error("Failed to send AREA_TYPE_CHANGED notifications for area {}: {}", areaId, ex.getMessage(), ex);
            }
            for (RequestNotice n : notices) {
                try {
                    notifService.createForUsers(
                            n.recipients(),
                            com.fa26se040.icss.enums.NotificationType.REQUEST_SYSTEM_CANCELLED,
                            "Đơn truy cập bị hệ thống huỷ",
                            n.message(),
                            n.requestId(),
                            InAppNotificationService.REF_TYPE_ACCESS_REQUEST
                    );
                } catch (Exception ex) {
                    log.error("Failed to send REQUEST_SYSTEM_CANCELLED for request {}: {}", n.requestId(), ex.getMessage(), ex);
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public List<AreaSimpleResponse> getAvailableAreasForRequest() {
        List<Area> areas = areaRepository.findAvailableForRequest(List.of(
                AreaLevel.INTERNAL_CONFIDENTIAL,
                AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED,
                AreaLevel.HIGHLY_CONFIDENTIAL
        ));
        return areas.stream()
                .map(a -> new AreaSimpleResponse(
                        a.getId(),
                        a.getName(),
                        a.getAreaLevel(),
                        a.getBuilding(),
                        a.getFloor()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AreaListItemResponse> getAreas(String keyword, AreaLevel areaLevel, String building, Boolean isActive, Pageable pageable) {
        String cleanKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        String cleanBuilding = (building != null && !building.trim().isEmpty()) ? building.trim() : null;
        Page<Area> page = areaRepository.searchAreas(cleanKeyword, areaLevel, cleanBuilding, isActive, pageable);
        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();
        return page.map(a -> mapToAreaListItemResponse(a, computeDiffersFromPreset(a, presetMap)));
    }

    @Transactional(readOnly = true)
    public AreaResponse getAreaById(UUID id) {
        Area area = areaRepository.findById(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();
        return mapToAreaResponse(area, computeDiffersFromPreset(area, presetMap));
    }

    @Transactional(readOnly = true)
    public AreaDependencyResponse getDependencies(UUID id) {
        areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        return dependencyChecker.check(id);
    }

    @Transactional
    public AreaResponse create(AreaCreateRequest req, String actorEmail) {
        String name = areaValidator.validateAndNormalizeName(req.getName());

        if (req.getAreaLevel() == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_003);
        }

        resolveActorId(actorEmail);

        // Fail-closed, khớp DEFAULT trong V38 khi thiếu preset (level 3, explicit_authorization_required = true)
        int areaAccessLevel = 3;
        boolean explicitAuthRequired = true;

        Optional<AreaLevelPreset> presetOpt = areaLevelPresetRepository.findById(req.getAreaLevel());
        if (presetOpt.isPresent()) {
            AreaLevelPreset preset = presetOpt.get();
            areaAccessLevel = preset.getAreaAccessLevel();
            explicitAuthRequired = preset.getExplicitAuthorizationRequired();
        } else {
            log.warn("Không tìm thấy preset cho area_level = {}, áp dụng fallback fail-closed (level 3, explicit_authorization_required = true)",
                    req.getAreaLevel());
        }

        com.fa26se040.icss.entity.Floor targetFloor = null;
        if (req.getFloorId() != null) {
            targetFloor = floorRepository.findById(req.getFloorId()).orElse(null);
        } else if (req.getBuilding() != null && !req.getBuilding().trim().isEmpty() && req.getFloor() != null && !req.getFloor().trim().isEmpty()) {
            targetFloor = floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase(
                    req.getBuilding().trim(), req.getFloor().trim()).orElse(null);
        }

        if (targetFloor == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_021);
        }

        if (areaRepository.existsByFloorIdAndNameIgnoreCase(targetFloor.getId(), name)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_020);
        }

        String buildingVal = targetFloor.getBuilding() != null
                ? targetFloor.getBuilding().getCode()
                : (req.getBuilding() != null ? req.getBuilding().trim() : null);

        String floorVal = targetFloor.getFloorCode();

        Area area = Area.builder()
                .name(name)
                .areaLevel(req.getAreaLevel())
                .areaAccessLevel(areaAccessLevel)
                .explicitAuthorizationRequired(explicitAuthRequired)
                .floorEntity(targetFloor)
                .building(buildingVal)
                .floor(floorVal)
                .centerLatitude(req.getCenterLatitude())
                .centerLongitude(req.getCenterLongitude())
                .isActive(true)
                .build();

        try {
            Area savedArea = areaRepository.saveAndFlush(area);
            User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
            auditService.record(
                    AuditTargetType.AREA,
                    AuditAction.CREATE,
                    savedArea.getId().toString(),
                    savedArea,
                    null,
                    null,
                    AreaSnapshot.from(savedArea),
                    null,
                    actor
            );
            return mapToAreaResponse(savedArea);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Data integrity violation on creating area [{}]: {}", name, ex.getMessage());
            String msg = (ex.getMessage() + " " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
            if (msg.contains("ux_areas_floor_name_active")) {
                throw new AreaException(AreaErrorCode.ERR_AREA_020);
            }
            throw ex;
        }
    }

    @Transactional
    public AreaResponse update(UUID id, AreaUpdateRequest req, String actorEmail) {
        // 1. Hợp lệ dữ liệu (400) trước khi khoá: loại, version (BR-TC-13), lý do nếu có phải 10–500 (TC-12)
        if (req.getAreaLevel() == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_003);
        }
        requireVersion(req.getVersion());
        String reason = normalizeReason(req.getReason(), false);
        // Đổi loại bắt buộc lý do (TC-02): đọc loại hiện tại không khoá, không nạp entity; kiểm lại sau khi khoá
        AreaLevel peekLevel = areaRepository.findAreaLevelById(id).orElse(null);
        if (peekLevel != null && peekLevel != req.getAreaLevel() && reason == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_050);
        }

        // 2. Khoá area, so version sau khi khoá (409 ERR_AREA_045)
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        checkVersion(area, req.getVersion());
        AreaRowState before = AreaRowState.of(area);
        OffsetDateTime now = OffsetDateTime.now();
        boolean typeChange = area.getAreaLevel() != req.getAreaLevel();
        if (typeChange && reason == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_050);
        }

        AreaSnapshot beforeSnapshot = AreaSnapshot.from(area);

        // BR-41: Block changing building or floor when the Area already has geometry
        if (area.getGeometry() != null) {
            String newBuilding = req.getBuilding() != null ? req.getBuilding().trim() : null;
            String currentBuilding = area.getBuilding() != null ? area.getBuilding().trim() : null;
            boolean buildingChanged = (newBuilding == null && currentBuilding != null)
                    || (newBuilding != null && !newBuilding.equalsIgnoreCase(currentBuilding));

            String newFloor = req.getFloor() != null ? req.getFloor().trim() : null;
            String currentFloor = area.getFloor() != null ? area.getFloor().trim() : null;
            boolean floorChanged = (newFloor == null && currentFloor != null)
                    || (newFloor != null && !newFloor.equalsIgnoreCase(currentFloor));

            if (buildingChanged || floorChanged) {
                throw new AreaException(AreaErrorCode.ERR_AREA_014);
            }
        }

        String name = areaValidator.validateAndNormalizeName(req.getName());

        // 3. Đổi loại: đánh giá lại trên dữ liệu đã khoá (cùng hàm với xem trước — TC-03b); bị chặn -> không đổi gì (TC-11)
        TypeChangeEvaluation evaluation = null;
        if (typeChange) {
            evaluation = evaluateTypeChange(area, req.getAreaLevel(), now);
            if (!evaluation.blockers().isEmpty()) {
                throw evaluation.blockers().get(0);
            }
        }

        resolveActorId(actorEmail);

        com.fa26se040.icss.entity.Floor targetFloor = area.getFloorEntity();
        if (req.getFloorId() != null) {
            targetFloor = floorRepository.findById(req.getFloorId()).orElse(null);
        } else if (req.getBuilding() != null && !req.getBuilding().trim().isEmpty() && req.getFloor() != null && !req.getFloor().trim().isEmpty()) {
            targetFloor = floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase(
                    req.getBuilding().trim(), req.getFloor().trim()).orElse(null);
        }

        if (targetFloor == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_021);
        }

        if (areaRepository.existsByFloorIdAndNameIgnoreCaseExcludingId(id, targetFloor.getId(), name)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_020);
        }

        String buildingVal = targetFloor.getBuilding() != null
                ? targetFloor.getBuilding().getCode()
                : (req.getBuilding() != null ? req.getBuilding().trim() : area.getBuilding());

        String floorVal = targetFloor.getFloorCode();

        area.setName(name);
        area.setAreaLevel(req.getAreaLevel());
        area.setFloorEntity(targetFloor);
        area.setBuilding(buildingVal);
        area.setFloor(floorVal);
        area.setCenterLatitude(req.getCenterLatitude());
        area.setCenterLongitude(req.getCenterLongitude());
        if (typeChange) {
            // TC-04: áp lại preset của loại mới (thiếu preset -> 3/true)
            area.setAreaAccessLevel(evaluation.newAccessLevel());
            area.setExplicitAuthorizationRequired(evaluation.newExplicitAuthorizationRequired());
        }
        bumpVersionIfChanged(area, before);

        // Mọi audit của thao tác (CHANGE_TYPE + huỷ đơn của hệ thống) dùng chung một correlation
        boolean ownCorrelation = com.fa26se040.icss.context.AuditContext.getCorrelationId() == null;
        if (ownCorrelation) {
            com.fa26se040.icss.context.AuditContext.setCorrelationId(UUID.randomUUID());
        }
        try {
            Area savedArea = areaRepository.saveAndFlush(area);
            User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
            if (typeChange) {
                auditService.record(
                        AuditTargetType.AREA,
                        AuditAction.CHANGE_TYPE,
                        savedArea.getId().toString(),
                        savedArea,
                        null,
                        beforeSnapshot,
                        AreaSnapshot.from(savedArea),
                        reason,
                        actor
                );
                List<com.fa26se040.icss.entity.AccessRequest> cancelled =
                        cancelRequestsBySystem(savedArea, evaluation.allToCancel(), now);
                notifyAfterTypeChange(savedArea, evaluation.currentLevel(), evaluation.newLevel(), reason, cancelled);
            } else {
                auditService.record(
                        AuditTargetType.AREA,
                        AuditAction.UPDATE,
                        savedArea.getId().toString(),
                        savedArea,
                        null,
                        beforeSnapshot,
                        AreaSnapshot.from(savedArea),
                        reason,
                        actor
                );
            }
            return mapToAreaResponse(savedArea);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Data integrity violation on updating area [{}]: {}", name, ex.getMessage());
            String msg = (ex.getMessage() + " " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
            if (msg.contains("ux_areas_floor_name_active")) {
                throw new AreaException(AreaErrorCode.ERR_AREA_020);
            }
            throw ex;
        } finally {
            if (ownCorrelation) {
                com.fa26se040.icss.context.AuditContext.clearCorrelationId();
            }
        }
    }

    @Transactional
    public AreaGeometryResponse saveGeometry(UUID id, AreaGeometry geometry, Long version, String actorEmail) {
        // Hợp lệ dữ liệu trước khi khoá: version (BR-TC-13) + hình dạng polygon
        requireVersion(version);
        geometryValidator.validateVertices(geometry);

        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        checkVersion(area, version);
        AreaRowState before = AreaRowState.of(area);

        List<Area> existingOnFloor = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(
                area.getBuilding(),
                area.getFloor()
        );

        geometryValidator.validate(geometry, area.getId(), area.getBuilding(), area.getFloor(), existingOnFloor);

        geometry.setType("polygon");
        geometry.setVersion(1);

        resolveActorId(actorEmail);

        AreaGeometrySnapshot beforeSnapshot = AreaGeometrySnapshot.from(area.getGeometry());

        area.setGeometry(geometry);
        bumpVersionIfChanged(area, before);

        Area savedArea = areaRepository.save(area);
        User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
        auditService.record(
                AuditTargetType.AREA,
                AuditAction.UPDATE_GEOMETRY,
                savedArea.getId().toString(),
                savedArea,
                null,
                beforeSnapshot,
                AreaGeometrySnapshot.from(savedArea.getGeometry()),
                null,
                actor
        );
        return new AreaGeometryResponse(
                savedArea.getId(),
                savedArea.getName(),
                savedArea.getAreaLevel(),
                savedArea.getIsActive(),
                savedArea.getGeometry()
        );
    }

    @Transactional
    public void deleteGeometry(UUID id, Long version, String actorEmail) {
        requireVersion(version);
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        checkVersion(area, version);

        if (area.getGeometry() == null) {
            return;
        }
        AreaRowState before = AreaRowState.of(area);

        resolveActorId(actorEmail);
        AreaGeometrySnapshot beforeSnapshot = AreaGeometrySnapshot.from(area.getGeometry());

        area.setGeometry(null);
        bumpVersionIfChanged(area, before);
        Area savedArea = areaRepository.save(area);

        User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
        auditService.record(
                AuditTargetType.AREA,
                AuditAction.DELETE_GEOMETRY,
                savedArea.getId().toString(),
                savedArea,
                null,
                beforeSnapshot,
                null,
                null,
                actor
        );
    }

    @Transactional(readOnly = true)
    public List<AreaGeometryResponse> getGeometriesByBuildingAndFloor(String building, String floor) {
        List<Area> areas = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(building, floor);
        return areas.stream()
                .map(a -> new AreaGeometryResponse(
                        a.getId(),
                        a.getName(),
                        a.getAreaLevel(),
                        a.getIsActive(),
                        a.getGeometry()
                ))
                .toList();
    }

    @Transactional
    public void deactivate(UUID id, String actorEmail) {
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        if (eventScheduleRepository != null && id != null) {
            List<com.fa26se040.icss.entity.AreaEventSchedule> pending = eventScheduleRepository
                    .findByAreaIdAndStatusOrderByStartAtAsc(id, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
            if (!pending.isEmpty()) {
                throw new AreaException(AreaErrorCode.ERR_AREA_042, buildPendingSchedulesErrorMessage(pending));
            }
        }

        AreaDependencyResponse dep = dependencyChecker.check(id);
        if (!dep.canDeactivate()) {
            AreaDependencyResponse.Blocker firstBlocker = dep.blockers().get(0);
            throw new AreaException(firstBlocker.errorCode(), firstBlocker.count());
        }

        resolveActorId(actorEmail);
        AreaSnapshot beforeSnapshot = AreaSnapshot.from(area);
        AreaRowState before = AreaRowState.of(area);

        area.setIsActive(false);
        area.setDeletedAt(OffsetDateTime.now());
        bumpVersionIfChanged(area, before);

        Area savedArea = areaRepository.save(area);

        User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
        auditService.record(
                AuditTargetType.AREA,
                AuditAction.DEACTIVATE,
                savedArea.getId().toString(),
                savedArea,
                null,
                beforeSnapshot,
                AreaSnapshot.from(savedArea),
                null,
                actor
        );
    }

    private String buildPendingSchedulesErrorMessage(List<com.fa26se040.icss.entity.AreaEventSchedule> schedules) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        java.time.format.DateTimeFormatter dtfDateHour = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zone);
        java.time.format.DateTimeFormatter dtfTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone);

        List<String> items = new ArrayList<>();
        int limit = Math.min(schedules.size(), 5);
        for (int i = 0; i < limit; i++) {
            com.fa26se040.icss.entity.AreaEventSchedule s = schedules.get(i);
            String timeStr;
            if (s.getStartAt().atZoneSameInstant(zone).toLocalDate().equals(s.getEndAt().atZoneSameInstant(zone).toLocalDate())) {
                timeStr = dtfDateHour.format(s.getStartAt()) + "–" + dtfTime.format(s.getEndAt());
            } else {
                timeStr = dtfDateHour.format(s.getStartAt()) + "–" + dtfDateHour.format(s.getEndAt());
            }
            String creatorName = (s.getCreatedBy() != null && s.getCreatedBy().getFullName() != null)
                    ? s.getCreatedBy().getFullName() : "Facility Manager";
            items.add(timeStr + " (" + creatorName + ")");
        }
        String listStr = String.join(", ", items);
        if (schedules.size() > 5) {
            listStr += ", và " + (schedules.size() - 5) + " lịch khác";
        }
        return String.format("Khu vực còn %d lịch sự kiện chưa diễn ra: %s. Liên hệ quản lý cơ sở vật chất để huỷ lịch trước.",
                schedules.size(), listStr);
    }

    @Transactional(readOnly = true)
    public AreaCameraResponse getCamerasForArea(UUID areaId) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        List<Camera> cameras = cameraRepository.findByAreaIdAndDeletedAtIsNull(areaId);
        List<CameraSimpleResponse> cameraResponses = cameras.stream()
                .map(c -> CameraSimpleResponse.builder()
                        .id(c.getId())
                        .cameraCode(c.getCameraCode())
                        .name(c.getName())
                        .status(c.getStatus())
                        .operationalStatus(c.getOperationalStatus())
                        .build())
                .collect(Collectors.toList());

        return AreaCameraResponse.builder()
                .areaId(area.getId())
                .areaName(area.getName())
                .cameras(cameraResponses)
                .build();
    }

    @Transactional
    public AreaCameraResponse updateCamerasForArea(UUID areaId, List<UUID> cameraIds) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        // BR-CAM-02: Khu vực phải đang hoạt động
        if (!Boolean.TRUE.equals(area.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        List<Camera> camerasToAssign;
        if (cameraIds == null || cameraIds.isEmpty()) {
            camerasToAssign = List.of();
        } else {
            camerasToAssign = cameraRepository.findAllById(cameraIds);
            if (camerasToAssign.size() != cameraIds.size()) {
                throw new CameraException(CameraErrorCode.ERR_CAM_002);
            }
            // BR-CAM-03: Không thể gán camera DECOMMISSIONED
            boolean hasDecommissioned = camerasToAssign.stream()
                    .anyMatch(c -> c.getStatus() != CameraStatus.ACTIVE);
            if (hasDecommissioned) {
                throw new CameraException(CameraErrorCode.ERR_MAP_002);
            }
        }

        // BR-CAM-04: Lấy danh sách camera hiện đang gán cho khu vực này
        List<Camera> currentlyAssigned = cameraRepository.findByAreaIdAndDeletedAtIsNull(areaId);
        Set<UUID> newCameraIdSet = (cameraIds != null) ? new HashSet<>(cameraIds) : Set.of();
        AreaCamerasSnapshot beforeSnapshot = AreaCamerasSnapshot.from(currentlyAssigned.stream().map(Camera::getId).toList());

        List<Camera> toUpdate = new ArrayList<>();
        // 1. Unassign camera cũ không còn trong danh sách mới
        for (Camera currentCam : currentlyAssigned) {
            if (!newCameraIdSet.contains(currentCam.getId())) {
                currentCam.setArea(null);
                toUpdate.add(currentCam);
            }
        }

        // 2. Gán camera mới (tự động chuyển khu vực nếu trước đó thuộc khu vực khác)
        for (Camera cam : camerasToAssign) {
            cam.setArea(area);
            toUpdate.add(cam);
        }

        if (!toUpdate.isEmpty()) {
            cameraRepository.saveAll(toUpdate);
        }

        AreaCamerasSnapshot afterSnapshot = AreaCamerasSnapshot.from(cameraIds != null ? cameraIds : List.of());
        auditService.record(
                AuditTargetType.AREA,
                AuditAction.UPDATE_CAMERAS,
                area.getId().toString(),
                area,
                null,
                beforeSnapshot,
                afterSnapshot,
                null
        );

        return getCamerasForArea(area.getId());
    }

    private UUID resolveActorId(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    @Transactional
    public AreaResponse updateAccessRules(UUID id, AreaAccessRulesUpdateRequest req) {
        return updateAccessRules(id, req, null);
    }

    @Transactional
    public AreaResponse updateAccessRules(UUID id, AreaAccessRulesUpdateRequest req, String actorEmail) {
        requireVersion(req.version());
        Area area = areaRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        checkVersion(area, req.version());
        AreaRowState before = AreaRowState.of(area);

        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();

        // BR-AL-06: Thao tác không làm thay đổi giá trị (new == old) -> không ghi log, trả về trạng thái hiện tại
        if (java.util.Objects.equals(area.getAreaAccessLevel(), req.areaAccessLevel()) &&
                java.util.Objects.equals(area.getExplicitAuthorizationRequired(), req.explicitAuthorizationRequired())) {
            log.info("Area {} access rules unchanged, skipping audit log", id);
            return mapToAreaResponse(area, computeDiffersFromPreset(area, presetMap));
        }

        Integer oldAccessLevel = area.getAreaAccessLevel();
        Boolean oldExplicit = area.getExplicitAuthorizationRequired();

        area.setAreaAccessLevel(req.areaAccessLevel());
        area.setExplicitAuthorizationRequired(req.explicitAuthorizationRequired());
        area.setUpdatedAt(OffsetDateTime.now());
        bumpVersionIfChanged(area, before);

        Area savedArea = areaRepository.save(area);

        if (actorEmail != null) {
            User actor = userRepository.findByEmail(actorEmail)
                    .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

            com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot oldSnapshot =
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot(oldAccessLevel, oldExplicit);
            com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot newSnapshot =
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot(
                            savedArea.getAreaAccessLevel(),
                            savedArea.getExplicitAuthorizationRequired()
                    );

            auditService.record(
                    com.fa26se040.icss.enums.AuditTargetType.AREA_ACCESS_RULES,
                    com.fa26se040.icss.enums.AuditAction.UPDATE,
                    savedArea.getId().toString(),
                    savedArea,
                    null,
                    oldSnapshot,
                    newSnapshot,
                    req.reason(),
                    actor
            );
        }

        return mapToAreaResponse(savedArea, computeDiffersFromPreset(savedArea, presetMap));
    }

    @Transactional(readOnly = true)
    public List<AreaMapPinResponse> getMapPins(String building) {
        String cleanBuilding = (building != null && !building.trim().isEmpty()) ? building.trim() : null;
        List<Area> areas = (cleanBuilding != null)
                ? areaRepository.findAreaMapPinsByBuilding(cleanBuilding)
                : areaRepository.findAllAreaMapPins();
        return areas.stream()
                .map(a -> AreaMapPinResponse.builder()
                        .id(a.getId())
                        .name(a.getName())
                        .areaLevel(a.getAreaLevel())
                        .areaAccessLevel(a.getAreaAccessLevel())
                        .building(a.getBuilding())
                        .floor(a.getFloor())
                        .centerLatitude(a.getCenterLatitude())
                        .centerLongitude(a.getCenterLongitude())
                        .isActive(a.getIsActive())
                        .build())
                .toList();
    }

    public int getEventModeMaxHours() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_MAX_HOURS);
        if (val < 1 || val > 72) {
            log.warn("Config EVENT_MODE_MAX_HOURS value [{}] out of range [1-72]. Using default: 12", val);
            return 12;
        }
        return val;
    }

    public int getEventModeWindowDays() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_WINDOW_DAYS);
        if (val < 1 || val > 30) {
            log.warn("Config EVENT_MODE_WINDOW_DAYS value [{}] out of range [1-30]. Using default: 7", val);
            return 7;
        }
        return val;
    }

    public int getEventModeBudgetHours() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_BUDGET_HOURS);
        if (val < 1 || val > 720) {
            log.warn("Config EVENT_MODE_BUDGET_HOURS value [{}] out of range [1-720]. Using default: 48", val);
            return 48;
        }
        return val;
    }

    public int getEventModeMinMinutes() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_MIN_MINUTES);
        if (val < 1 || val > 240) {
            log.warn("Config EVENT_MODE_MIN_MINUTES value [{}] out of range [1-240]. Using default: 15", val);
            return 15;
        }
        return val;
    }

    public int getEventModeGraceMinutes() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_GRACE_MINUTES);
        if (val < 0 || val > 240) {
            return 15;
        }
        return val;
    }

    public int getEventModeExpiryReminderMinutes() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES);
        if (val < 0 || val > 240) {
            return 30;
        }
        return val;
    }

    public int getEventModeScheduleMaxLeadDays() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS);
        if (val < 1 || val > 180) {
            log.warn("Config EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS value [{}] out of range [1-180]. Using default: 30", val);
            return 30;
        }
        return val;
    }

    public int getEventModeMaxSchedulesPerArea() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_MAX_SCHEDULES_PER_AREA);
        if (val < 1 || val > 20) {
            log.warn("Config EVENT_MODE_MAX_SCHEDULES_PER_AREA value [{}] out of range [1-20]. Using default: 5", val);
            return 5;
        }
        return val;
    }

    public int getEventModeScheduleReminderMinutes() {
        int val = systemConfigService.getInt(com.fa26se040.icss.enums.ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES);
        if (val < 0 || val > 1440) {
            log.warn("Config EVENT_MODE_SCHEDULE_REMINDER_MINUTES value [{}] out of range [0-1440]. Using default: 30", val);
            return 30;
        }
        return val;
    }

    public double calculateUsedHoursInWindow(UUID areaId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<com.fa26se040.icss.entity.AreaEventSession> sessions = eventSessionRepository.findSessionsInWindow(areaId, windowStart, windowEnd);
        double used = 0.0;
        for (com.fa26se040.icss.entity.AreaEventSession s : sessions) {
            OffsetDateTime sStart = s.getStartedAt();
            OffsetDateTime sEnd = s.getActualEnd() != null ? s.getActualEnd() : s.getPlannedEnd();
            if (sEnd != null && sEnd.isAfter(sStart)) {
                OffsetDateTime overlapStart = sStart.isAfter(windowStart) ? sStart : windowStart;
                OffsetDateTime overlapEnd = sEnd.isBefore(windowEnd) ? sEnd : windowEnd;
                if (overlapEnd.isAfter(overlapStart)) {
                    long minutes = java.time.Duration.between(overlapStart, overlapEnd).toMinutes();
                    used += minutes / 60.0;
                }
            }
        }
        return used;
    }

    public double calculateTotalHoursInWindow(UUID areaId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<com.fa26se040.icss.dto.area.TimeInterval> intervals = new java.util.ArrayList<>();
        List<com.fa26se040.icss.entity.AreaEventSession> sessions = eventSessionRepository.findByAreaId(areaId);
        for (com.fa26se040.icss.entity.AreaEventSession s : sessions) {
            OffsetDateTime sStart = s.getStartedAt();
            OffsetDateTime sEnd = s.getActualEnd() != null ? s.getActualEnd() : s.getPlannedEnd();
            if (sStart != null && sEnd != null && sEnd.isAfter(sStart)) {
                intervals.add(new com.fa26se040.icss.dto.area.TimeInterval(sStart, sEnd));
            }
        }
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedules = eventScheduleRepository
                .findByAreaIdAndStatus(areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        for (com.fa26se040.icss.entity.AreaEventSchedule s : schedules) {
            intervals.add(new com.fa26se040.icss.dto.area.TimeInterval(s.getStartAt(), s.getEndAt()));
        }
        return calculateTotalOverlapHours(intervals, windowStart, windowEnd);
    }

    public List<com.fa26se040.icss.entity.AreaEventSchedule> getViolatingSchedules(UUID areaId, int newMaxHours, int newWindowDays, int newBudgetHours) {
        List<com.fa26se040.icss.entity.AreaEventSchedule> result = new java.util.ArrayList<>();
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedules = eventScheduleRepository
                .findByAreaIdAndStatus(areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        for (com.fa26se040.icss.entity.AreaEventSchedule s : schedules) {
            long durationMinutes = java.time.Duration.between(s.getStartAt(), s.getEndAt()).toMinutes();
            if (durationMinutes > newMaxHours * 60L) {
                result.add(s);
                continue;
            }
            OffsetDateTime wStart = s.getEndAt().minusDays(newWindowDays);
            OffsetDateTime wEnd = s.getEndAt();
            double usedInWindow = calculateTotalHoursInWindow(areaId, wStart, wEnd);
            if (usedInWindow > newBudgetHours + 1e-4) {
                result.add(s);
            }
        }
        return result;
    }

    public java.util.List<Area> findAreasViolatingNewEventLimits(int newMaxHours, int newWindowDays, int newBudgetHours) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Area> allAreas = areaRepository.findAll();
        List<Area> violating = new java.util.ArrayList<>();

        for (Area area : allAreas) {
            boolean isViolating = false;
            // 1. Check currently active event
            if (area.isEventActive(now)) {
                OffsetDateTime openUntil = area.getOpenUntil();
                if (openUntil != null) {
                    // (a) open_until − now > MAX_HOURS mới
                    if (openUntil.isAfter(now.plusHours(newMaxHours))) {
                        isViolating = true;
                    } else {
                        // (b) giờ đã dùng trong [open_until − WINDOW_DAYS mới, open_until] > BUDGET_HOURS mới
                        OffsetDateTime windowStart = openUntil.minusDays(newWindowDays);
                        OffsetDateTime windowEnd = openUntil;
                        double used = calculateUsedHoursInWindow(area.getId(), windowStart, windowEnd);
                        if (used > newBudgetHours + 1e-4) {
                            isViolating = true;
                        }
                    }
                }
            }

            // 2. Check scheduled events (BR-ES-07)
            if (!isViolating) {
                List<com.fa26se040.icss.entity.AreaEventSchedule> violatingSchedules =
                        getViolatingSchedules(area.getId(), newMaxHours, newWindowDays, newBudgetHours);
                if (!violatingSchedules.isEmpty()) {
                    isViolating = true;
                }
            }

            if (isViolating) {
                violating.add(area);
            }
        }
        return violating;
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "${icss.scheduler.notification-event-mode-expiring-cron:0 */1 * * * *}")
    @Transactional
    public int scanAndSendEventModeExpiryReminders() {
        int reminderMinutes = getEventModeExpiryReminderMinutes();
        if (reminderMinutes <= 0) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime reminderThreshold = now.plusMinutes(reminderMinutes);

        List<com.fa26se040.icss.entity.AreaEventSession> activeSessions =
                eventSessionRepository.findSessionsNearingExpiry(now, reminderThreshold);

        if (activeSessions.isEmpty()) {
            return 0;
        }

        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();

        int count = 0;
        for (com.fa26se040.icss.entity.AreaEventSession session : activeSessions) {
            Area area = areaRepository.findById(session.getArea().getId()).orElse(session.getArea());
            if (area == null || !area.isEventActive(now)) {
                continue;
            }

            session.setExpiryRemindedAt(now);
            eventSessionRepository.save(session);
            count++;

            if (notifService != null) {
                User startedBy = session.getStartedBy();
                List<User> recipients = new java.util.ArrayList<>();
                if (startedBy != null && Boolean.TRUE.equals(startedBy.getIsActive()) && startedBy.getRole() == com.fa26se040.icss.enums.Role.FACILITY_MANAGER) {
                    recipients.add(startedBy);
                } else {
                    recipients.addAll(userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.FACILITY_MANAGER));
                }

                if (!recipients.isEmpty()) {
                    String areaName = area.getName();
                    String plannedStr = dtf.format(session.getPlannedEnd());
                    notifService.createForUsers(
                            recipients,
                            com.fa26se040.icss.enums.NotificationType.EVENT_MODE_EXPIRING,
                            "Chế độ sự kiện sắp hết hạn",
                            String.format("Chế độ sự kiện tại khu vực %s sẽ kết thúc lúc %s. Vui lòng kiểm tra hoặc điều chỉnh giờ kết thúc nếu cần.", areaName, plannedStr),
                            area.getId(),
                            "AREA"
                    );
                }
            }
        }
        return count;
    }

    /**
     * BR-EV-A5: câu ERR_AREA_030 theo trạng thái thật của khu vực (4 dạng, giờ "HH:mm dd/MM/yyyy"):
     * đang mở đến {openUntil} · đã tắt lúc {actual_end} (phiên gần nhất có ended_by) ·
     * đã hết hạn lúc {planned_end} (đóng do hết giờ, hoặc quá planned_end chưa đóng) · đang tắt (chưa có phiên nào).
     */
    private String buildEventModeStatusChangedMessage(Area area, OffsetDateTime now) {
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        String prefix = "Trạng thái sự kiện đã thay đổi: ";
        String suffix = ". Vui lòng tải lại trang.";
        if (area.isEventActive(now)) {
            return prefix + "đang mở đến " + dtf.format(area.getOpenUntil()) + suffix;
        }
        com.fa26se040.icss.entity.AreaEventSession last = eventSessionRepository
                .findTopByAreaIdOrderByStartedAtDesc(area.getId()).orElse(null);
        if (last != null) {
            if (last.getActualEnd() != null && last.getEndedBy() != null) {
                return prefix + "đã tắt lúc " + dtf.format(last.getActualEnd()) + suffix;
            }
            if (last.getActualEnd() != null || !last.getPlannedEnd().isAfter(now)) {
                return prefix + "đã hết hạn lúc " + dtf.format(last.getPlannedEnd()) + suffix;
            }
        } else if (area.getOpenUntil() != null && !area.getOpenUntil().isAfter(now)) {
            // Dữ liệu cũ: cờ sự kiện không kèm phiên, đã quá giờ kết thúc
            return prefix + "đã hết hạn lúc " + dtf.format(area.getOpenUntil()) + suffix;
        }
        return prefix + "đang tắt" + suffix;
    }

    private void executeAfterCommitOrImmediately(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
        } else {
            action.run();
        }
    }

    private void sendGuardEventModeChangedNotification(Area area, com.fa26se040.icss.enums.AuditAction action, User actor, OffsetDateTime openUntil) {
        sendGuardEventModeChangedNotification(area, action, actor, openUntil, false);
    }

    private void sendGuardEventModeChangedNotification(Area area, com.fa26se040.icss.enums.AuditAction action, User actor, OffsetDateTime openUntil, boolean afterCommit) {
        Runnable sendTask = () -> {
            InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();
            if (notifService == null) {
                return;
            }
            List<User> activeGuards = userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.GUARD);
            if (activeGuards.isEmpty()) {
                return;
            }

            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
            String actionText;
            if (action == com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE) {
                actionText = "Bật";
            } else if (action == com.fa26se040.icss.enums.AuditAction.EXTEND_EVENT_MODE) {
                actionText = "Điều chỉnh giờ kết thúc";
            } else {
                actionText = "Tắt";
            }

            String actorName = (actor != null && actor.getFullName() != null) ? actor.getFullName() : "Facility Manager";
            String message;
            if (openUntil != null) {
                message = String.format("Khu vực %s: %s chế độ sự kiện đến %s bởi %s.", area.getName(), actionText, dtf.format(openUntil), actorName);
            } else {
                message = String.format("Khu vực %s: %s chế độ sự kiện bởi %s.", area.getName(), actionText, actorName);
            }

            notifService.createForUsers(
                    activeGuards,
                    com.fa26se040.icss.enums.NotificationType.EVENT_MODE_CHANGED,
                    "Chế độ sự kiện khu vực thay đổi",
                    message,
                    area.getId(),
                    "AREA"
            );
        };

        if (afterCommit) {
            executeAfterCommitOrImmediately(sendTask);
        } else {
            sendTask.run();
        }
    }

    @Transactional
    public AreaResponse updateEventMode(UUID id, AreaEventModeUpdateRequest req, String actorEmail) {
        log.info("Updating event mode for area {}: action={}, openUntil={}, reasonCode={}, version={}",
                id, req != null ? req.action() : null, req != null ? req.openUntil() : null,
                req != null ? req.reasonCode() : null, req != null ? req.version() : null);

        // 1. Hợp lệ dữ liệu (400) TRƯỚC khi khoá (BR-EV-A4)
        if (req == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        // EV-A1: có khoá "enabled" (kể cả null) -> 046, kiểm trước action
        if (req.enabled() != null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_046);
        }
        com.fa26se040.icss.enums.EventModeAction eventAction = req.action();
        if (eventAction == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_047);
        }
        String rawNote = req.note();
        if (rawNote == null || rawNote.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String trimmedNote = rawNote.trim();
        if (trimmedNote.length() < 10 || trimmedNote.length() > 500) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String rawReasonCode = req.reasonCode();
        if (rawReasonCode == null || rawReasonCode.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String normReasonCode = rawReasonCode.trim().toUpperCase();
        requireVersion(req.version());

        // EV-A2: lý do tra theo (nhóm của action, code); không có trong nhóm -> 026
        String expectedActionType;
        com.fa26se040.icss.enums.AuditAction action;
        switch (eventAction) {
            case ENABLE -> {
                expectedActionType = "EVENT_ENABLE";
                action = com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE;
            }
            case ADJUST -> {
                expectedActionType = "EVENT_EXTEND";
                action = com.fa26se040.icss.enums.AuditAction.EXTEND_EVENT_MODE;
            }
            default -> {
                expectedActionType = "EVENT_DISABLE";
                action = com.fa26se040.icss.enums.AuditAction.DISABLE_EVENT_MODE;
            }
        }
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = findReasonInGroup(expectedActionType, normReasonCode);
        String reasonLabel = reasonItem.getLabel();

        boolean targetEnabled = eventAction != com.fa26se040.icss.enums.EventModeAction.DISABLE;
        if (targetEnabled && (req.openUntil() == null || !req.openUntil().isAfter(OffsetDateTime.now()))) {
            throw new AreaException(AreaErrorCode.ERR_AREA_023);
        }

        // 2. Khoá area (B2). Không tồn tại -> ERR_AREA_002
        Area area = areaRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        // now lấy SAU khi đã có khoá (R4)
        OffsetDateTime now = OffsetDateTime.now();

        // 3. Version so sau khi khoá (409 045), trước ý định / trạng thái
        checkVersion(area, req.version());
        AreaRowState before = AreaRowState.of(area);

        // Dọn phiên hết hạn trong cùng transaction, trên CHÍNH entity area đã khoá
        cleanupExpiredSessions(area, now);

        // 4. Ý định vs trạng thái sau khi khoá (EV-A3): ENABLE khi đang mở / ADJUST, DISABLE khi đang tắt hoặc hết hạn -> 030
        boolean activeNow = area.isEventActive(now);
        boolean oldEnabled = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime oldOpenUntil = area.getOpenUntil();
        if (activeNow == (eventAction == com.fa26se040.icss.enums.EventModeAction.ENABLE)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_030, buildEventModeStatusChangedMessage(area, now));
        }

        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();

        // (BR-EV-11) ADJUST với openUntil lệch giờ hiện tại < 1 giây -> thành công, không đổi, không audit, không thông báo
        if (eventAction == com.fa26se040.icss.enums.EventModeAction.ADJUST
                && oldOpenUntil != null
                && Math.abs(java.time.Duration.between(req.openUntil(), oldOpenUntil).toMillis()) < 1000) {
            log.info("Area {} extend event mode unchanged (<1s diff), skipping audit and update", id);
            return mapToAreaResponse(area, computeDiffersFromPreset(area, presetMap));
        }

        // (BR-EV-03) ENABLE / ADJUST trên khu vực isActive = false -> từ chối (ERR_AREA_017). DISABLE vẫn cho phép.
        if (targetEnabled && (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        // 5. Loại khu vực (ERR_AREA_022), min minutes (ERR_AREA_031), MAX_HOURS (ERR_AREA_027), chồng lịch, ngân sách (ERR_AREA_028)
        if (!eventModeAllowed(area.getAreaLevel())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_022);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        UUID closedScheduleId = null;
        String closedScheduleStatus = null;
        if (targetEnabled) {
            OffsetDateTime targetOpenUntil = req.openUntil();

            int minMinutes = getEventModeMinMinutes();
            long reqMinutes = java.time.Duration.between(now, targetOpenUntil).toMinutes();
            if (reqMinutes < minMinutes) {
                throw new AreaException(AreaErrorCode.ERR_AREA_031, minMinutes);
            }

            int maxHours = getEventModeMaxHours();
            if (targetOpenUntil.isAfter(now.plusHours(maxHours))) {
                throw new AreaException(AreaErrorCode.ERR_AREA_027, maxHours);
            }

            // Kiểm tra chồng lấn với lịch SCHEDULED (BR-ES-15)
            List<com.fa26se040.icss.entity.AreaEventSchedule> scheduledList = eventScheduleRepository
                    .findByAreaIdAndStatus(id, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
            for (com.fa26se040.icss.entity.AreaEventSchedule s : scheduledList) {
                if (targetOpenUntil.isAfter(s.getStartAt()) && now.isBefore(s.getEndAt())) {
                    throw new AreaException(AreaErrorCode.ERR_AREA_041);
                }
            }

            // ADJUST: đóng phiên hiện tại tại now TRƯỚC khi tính ngân sách; phiên mới kế thừa lịch nguồn (BR-ES-S1)
            UUID inheritedScheduleId = null;
            if (eventAction == com.fa26se040.icss.enums.EventModeAction.ADJUST) {
                com.fa26se040.icss.entity.AreaEventSession currentSession = eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
                if (currentSession != null) {
                    inheritedScheduleId = currentSession.getScheduleId();
                    currentSession.setActualEnd(now);
                    currentSession.setEndedBy(actor);
                    eventSessionRepository.save(currentSession);
                }
            }

            validateEventBudget(id, new com.fa26se040.icss.dto.area.TimeInterval(now, targetOpenUntil), null);

            com.fa26se040.icss.entity.AreaEventSession newSession = com.fa26se040.icss.entity.AreaEventSession.builder()
                    .area(area)
                    .startedAt(now)
                    .plannedEnd(targetOpenUntil)
                    .actualEnd(null)
                    .startedBy(actor)
                    .expiryRemindedAt(null)
                    .createdAt(now)
                    .scheduleId(inheritedScheduleId)
                    .build();
            eventSessionRepository.save(newSession);

            area.setOpenToMembers(true);
            area.setOpenUntil(targetOpenUntil);
        } else {
            com.fa26se040.icss.entity.AreaEventSession currentSession = eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
            if (currentSession != null) {
                currentSession.setActualEnd(now);
                currentSession.setEndedBy(actor);
                eventSessionRepository.save(currentSession);
                // BR-ES-S1/S2: FM tắt phiên do lịch sinh ra -> lịch ENDED_EARLY, cùng transaction đóng phiên
                closedScheduleId = currentSession.getScheduleId();
                closedScheduleStatus = finishScheduleOfSession(currentSession, com.fa26se040.icss.enums.AreaEventScheduleStatus.ENDED_EARLY);
            }

            area.setOpenToMembers(false);
            area.setOpenUntil(null);
        }

        area.setUpdatedAt(now);
        bumpVersionIfChanged(area, before);
        Area savedArea = areaRepository.save(area);

        // 6. Ghi dữ liệu + 1 dòng audit AREA_EVENT_MODE + thông báo GUARD (B10)
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(oldEnabled, oldOpenUntil, null, null, null);
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot newSnapshot = closedScheduleStatus != null
                ? new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(savedArea.getOpenToMembers(), savedArea.getOpenUntil(),
                        normReasonCode, reasonLabel, trimmedNote, null, null, closedScheduleId, closedScheduleStatus)
                : new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(savedArea.getOpenToMembers(), savedArea.getOpenUntil(),
                        normReasonCode, reasonLabel, trimmedNote);

        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_MODE,
                action,
                savedArea.getId().toString(),
                savedArea,
                null,
                oldSnapshot,
                newSnapshot,
                trimmedNote,
                actor
        );

        sendGuardEventModeChangedNotification(savedArea, action, actor, savedArea.getOpenUntil());

        return mapToAreaResponse(savedArea, computeDiffersFromPreset(savedArea, presetMap));
    }

    /**
     * EV-A2 / ES-L2: tra lý do theo (nhóm, mã). Mã có ở nhóm khác -> 400 ERR_AREA_026; không tồn tại hoặc ngừng dùng -> 025.
     */
    private com.fa26se040.icss.entity.ReasonCatalog findReasonInGroup(String actionType, String normReasonCode) {
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository
                .findByActionTypeAndCode(actionType, normReasonCode)
                .orElse(null);
        if (reasonItem == null) {
            if (reasonCatalogRepository.existsByCode(normReasonCode)) {
                throw new AreaException(AreaErrorCode.ERR_AREA_026);
            }
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        if (!Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        return reasonItem;
    }

    /**
     * BR-ES-S1: phiên có lịch nguồn đóng -> lịch STARTED chuyển sang trạng thái đích (COMPLETED / ENDED_EARLY).
     * Trả trạng thái mới để ghi vào snapshot audit đóng phiên (BR-ES-S2); lịch không đổi -> null.
     */
    private String finishScheduleOfSession(com.fa26se040.icss.entity.AreaEventSession session,
                                           com.fa26se040.icss.enums.AreaEventScheduleStatus target) {
        if (session == null || session.getScheduleId() == null || eventScheduleRepository == null) {
            return null;
        }
        com.fa26se040.icss.entity.AreaEventSchedule schedule = eventScheduleRepository.findById(session.getScheduleId()).orElse(null);
        if (schedule == null || schedule.getStatus() != com.fa26se040.icss.enums.AreaEventScheduleStatus.STARTED) {
            return null;
        }
        schedule.setStatus(target);
        eventScheduleRepository.save(schedule);
        return target.name();
    }

    private AreaResponse mapToAreaResponse(Area area) {
        return mapToAreaResponse(area, false);
    }

    private AreaResponse mapToAreaResponse(Area area, boolean differsFromPreset) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean openToMembers = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime openUntil = area.getOpenUntil();
        boolean eventActive = area.isEventActive(now);
        EventTimeline timeline = computeEventTimeline(area, now);
        int upcomingScheduleCount = (eventScheduleRepository != null && area.getId() != null)
                ? (int) eventScheduleRepository.countByAreaIdAndStatus(area.getId(), com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                : 0;

        return AreaResponse.builder()
                .id(area.getId())
                .name(area.getName())
                .areaLevel(area.getAreaLevel())
                .areaAccessLevel(area.getAreaAccessLevel())
                .explicitAuthorizationRequired(area.getExplicitAuthorizationRequired())
                .building(area.getBuilding())
                .floor(area.getFloor())
                .geometry(area.getGeometry())
                .centerLatitude(area.getCenterLatitude())
                .centerLongitude(area.getCenterLongitude())
                .isActive(area.getIsActive())
                .createdAt(area.getCreatedAt())
                .updatedAt(area.getUpdatedAt())
                .differsFromPreset(differsFromPreset)
                .openToMembers(openToMembers)
                .openUntil(openUntil)
                .eventActive(eventActive)
                .eventStartedAt(timeline.startedAt())
                .eventStartedByName(timeline.startedByName())
                .eventLastAdjustedAt(timeline.lastAdjustedAt())
                .eventLastAdjustedByName(timeline.lastAdjustedByName())
                .upcomingScheduleCount(upcomingScheduleCount)
                .version(area.getVersion())
                .build();
    }

    private AreaListItemResponse mapToAreaListItemResponse(Area area, boolean differsFromPreset) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean openToMembers = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime openUntil = area.getOpenUntil();
        boolean eventActive = area.isEventActive(now);
        EventTimeline timeline = computeEventTimeline(area, now);
        int upcomingScheduleCount = (eventScheduleRepository != null && area.getId() != null)
                ? (int) eventScheduleRepository.countByAreaIdAndStatus(area.getId(), com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                : 0;

        return AreaListItemResponse.builder()
                .id(area.getId())
                .name(area.getName())
                .areaLevel(area.getAreaLevel())
                .areaAccessLevel(area.getAreaAccessLevel())
                .explicitAuthorizationRequired(area.getExplicitAuthorizationRequired())
                .building(area.getBuilding())
                .floor(area.getFloor())
                .isActive(area.getIsActive())
                .geometry(area.getGeometry())
                .hasGeometry(area.getGeometry() != null)
                .differsFromPreset(differsFromPreset)
                .centerLatitude(area.getCenterLatitude())
                .centerLongitude(area.getCenterLongitude())
                .openToMembers(openToMembers)
                .openUntil(openUntil)
                .eventActive(eventActive)
                .eventStartedAt(timeline.startedAt())
                .eventStartedByName(timeline.startedByName())
                .eventLastAdjustedAt(timeline.lastAdjustedAt())
                .eventLastAdjustedByName(timeline.lastAdjustedByName())
                .upcomingScheduleCount(upcomingScheduleCount)
                .version(area.getVersion())
                .build();
    }

    /**
     * Mốc thời gian của sự kiện đang mở, CHỈ để hiển thị.
     * Chuỗi phiên = phiên đang mở + các phiên liền trước nối tiếp (actual_end của phiên trước = started_at
     * của phiên sau, bằng nhau tuyệt đối — do thao tác ĐIỀU CHỈNH tạo ra trong cùng một request).
     * startedAt = started_at của phiên đầu chuỗi; lastAdjustedAt = started_at của phiên đang mở nếu chuỗi có >= 2 phiên.
     * Khu vực không đang mở sự kiện (BR-EV-04) hoặc không có phiên đang mở -> mọi field null.
     */
    private record EventTimeline(OffsetDateTime startedAt, String startedByName,
                                 OffsetDateTime lastAdjustedAt, String lastAdjustedByName) {
        private static final EventTimeline EMPTY = new EventTimeline(null, null, null, null);
    }

    private EventTimeline computeEventTimeline(Area area, OffsetDateTime now) {
        if (area == null || area.getId() == null || !area.isEventActive(now)) {
            return EventTimeline.EMPTY;
        }
        List<com.fa26se040.icss.entity.AreaEventSession> sessions =
                eventSessionRepository.findByAreaIdWithStarterOrderByStartedAtDesc(area.getId());
        if (sessions == null || sessions.isEmpty()) {
            return EventTimeline.EMPTY;
        }
        com.fa26se040.icss.entity.AreaEventSession current = sessions.get(0);
        if (current.getActualEnd() != null || current.getPlannedEnd() == null || !current.getPlannedEnd().isAfter(now)) {
            return EventTimeline.EMPTY;
        }
        com.fa26se040.icss.entity.AreaEventSession head = current;
        int chainLength = 1;
        for (int i = 1; i < sessions.size(); i++) {
            com.fa26se040.icss.entity.AreaEventSession prev = sessions.get(i);
            // ĐIỀU CHỈNH đóng phiên cũ và mở phiên mới bằng CÙNG một mốc now -> bằng nhau tuyệt đối (tới micro giây).
            // Tắt rồi bật lại là 2 request khác nhau -> hai mốc khác nhau -> không bị coi là cùng chuỗi.
            if (prev.getActualEnd() != null
                    && prev.getActualEnd().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        .isEqual(head.getStartedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
                head = prev;
                chainLength++;
            } else {
                break;
            }
        }
        String headName = head.getStartedBy() != null ? head.getStartedBy().getFullName() : null;
        if (chainLength == 1) {
            return new EventTimeline(head.getStartedAt(), headName, null, null);
        }
        String currentName = current.getStartedBy() != null ? current.getStartedBy().getFullName() : null;
        return new EventTimeline(head.getStartedAt(), headName, current.getStartedAt(), currentName);
    }

    public void validateEventBudget(UUID areaId, com.fa26se040.icss.dto.area.TimeInterval candidate, UUID excludeScheduleId) {
        int windowDays = getEventModeWindowDays();
        int budgetHours = getEventModeBudgetHours();
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                .ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

        // 1. Gather all intervals
        List<com.fa26se040.icss.dto.area.TimeInterval> intervals = new java.util.ArrayList<>();

        // Sessions:
        List<com.fa26se040.icss.entity.AreaEventSession> sessions = eventSessionRepository.findByAreaId(areaId);
        for (com.fa26se040.icss.entity.AreaEventSession s : sessions) {
            OffsetDateTime sStart = s.getStartedAt();
            OffsetDateTime sEnd = s.getActualEnd() != null ? s.getActualEnd() : s.getPlannedEnd();
            if (sStart != null && sEnd != null && sEnd.isAfter(sStart)) {
                intervals.add(new com.fa26se040.icss.dto.area.TimeInterval(sStart, sEnd));
            }
        }

        // Other SCHEDULED schedules:
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedules = eventScheduleRepository
                .findByAreaIdAndStatus(areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedulesToCheck = new java.util.ArrayList<>();
        for (com.fa26se040.icss.entity.AreaEventSchedule s : schedules) {
            if (excludeScheduleId != null && s.getId().equals(excludeScheduleId)) {
                continue;
            }
            intervals.add(new com.fa26se040.icss.dto.area.TimeInterval(s.getStartAt(), s.getEndAt()));
            if (s.getEndAt().isAfter(candidate.end())) {
                schedulesToCheck.add(s);
            }
        }

        // Candidate interval:
        intervals.add(candidate);

        // 2. Check window for candidate: [candidate.end - WINDOW_DAYS, candidate.end]
        OffsetDateTime candWindowStart = candidate.end().minusDays(windowDays);
        OffsetDateTime candWindowEnd = candidate.end();
        double candUsedHours = calculateTotalOverlapHours(intervals, candWindowStart, candWindowEnd);

        if (candUsedHours > budgetHours + 1e-4) {
            double candidateContrib = calculateSingleOverlapHours(candidate, candWindowStart, candWindowEnd);
            double usedBefore = candUsedHours - candidateContrib;
            double remainingHours = Math.max(0.0, budgetHours - usedBefore);
            String msg = String.format(java.util.Locale.US,
                    "Vượt quá ngân sách thời gian mở sự kiện của khu vực. Đã dùng: %.1f giờ trong %d ngày gần nhất, còn lại: %.1f giờ, yêu cầu: %.1f giờ (ngân sách: %d giờ / %d ngày).",
                    usedBefore, windowDays, remainingHours, candidateContrib, budgetHours, windowDays);
            throw new AreaException(AreaErrorCode.ERR_AREA_028, msg);
        }

        // 3. Check windows for each subsequent SCHEDULED schedule: [s.endAt - WINDOW_DAYS, s.endAt]
        for (com.fa26se040.icss.entity.AreaEventSchedule s : schedulesToCheck) {
            OffsetDateTime schedWindowStart = s.getEndAt().minusDays(windowDays);
            OffsetDateTime schedWindowEnd = s.getEndAt();
            double schedUsedHours = calculateTotalOverlapHours(intervals, schedWindowStart, schedWindowEnd);

            if (schedUsedHours > budgetHours + 1e-4) {
                String msg = String.format(java.util.Locale.US,
                        "Thao tác làm vượt quá ngân sách thời gian mở sự kiện cho lịch sự kiện kết thúc lúc %s. Tổng thời gian trong khung: %.1f giờ (ngân sách: %d giờ / %d ngày).",
                        dtf.format(schedWindowEnd), schedUsedHours, budgetHours, windowDays);
                throw new AreaException(AreaErrorCode.ERR_AREA_028, msg);
            }
        }
    }

    private double calculateTotalOverlapHours(List<com.fa26se040.icss.dto.area.TimeInterval> intervals,
                                              OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        double totalHours = 0.0;
        for (com.fa26se040.icss.dto.area.TimeInterval interval : intervals) {
            totalHours += calculateSingleOverlapHours(interval, windowStart, windowEnd);
        }
        return totalHours;
    }

    private double calculateSingleOverlapHours(com.fa26se040.icss.dto.area.TimeInterval interval,
                                               OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        OffsetDateTime oStart = interval.start().isAfter(windowStart) ? interval.start() : windowStart;
        OffsetDateTime oEnd = interval.end().isBefore(windowEnd) ? interval.end() : windowEnd;
        if (oEnd.isAfter(oStart)) {
            return java.time.Duration.between(oStart, oEnd).toMinutes() / 60.0;
        }
        return 0.0;
    }

    public void cleanupExpiredSessions(Area area, OffsetDateTime now) {
        if (area == null || area.getId() == null) {
            return;
        }
        com.fa26se040.icss.context.AuditContext.runAsSystem("EVENT_MODE_EXPIRY", () -> {
            List<com.fa26se040.icss.entity.AreaEventSession> expiredSessions =
                    eventSessionRepository.findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(area.getId(), now);
            for (com.fa26se040.icss.entity.AreaEventSession exp : expiredSessions) {
                exp.setActualEnd(exp.getPlannedEnd());
                exp.setEndedBy(null);
                eventSessionRepository.save(exp);
                // BR-ES-S1/S2: phiên do lịch sinh ra đóng vì hết giờ -> lịch COMPLETED, cùng transaction đóng phiên
                String scheduleStatus = finishScheduleOfSession(exp, com.fa26se040.icss.enums.AreaEventScheduleStatus.COMPLETED);

                auditService.record(
                        com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_MODE,
                        com.fa26se040.icss.enums.AuditAction.EXPIRE_EVENT_MODE,
                        area.getId().toString(),
                        area,
                        null,
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(true, exp.getPlannedEnd()),
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(false, null, null, null, null, exp.getId(), exp.getPlannedEnd(),
                                exp.getScheduleId(), scheduleStatus),
                        "Chế độ sự kiện tự động hết hạn",
                        com.fa26se040.icss.dto.audit.AuditActor.system("EVENT_MODE_EXPIRY")
                );
            }
            if (Boolean.TRUE.equals(area.getOpenToMembers()) && !area.isEventActive(now)) {
                area.setOpenToMembers(false);
                area.setOpenUntil(null);
                areaRepository.save(area);
            }
        });
    }

    private void sendGuardScheduleNotification(Area area, com.fa26se040.icss.enums.AuditAction action, com.fa26se040.icss.entity.AreaEventSchedule schedule) {
        executeAfterCommitOrImmediately(() -> {
            InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();
            if (notifService == null) {
                return;
            }
            List<User> activeGuards = userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.GUARD);
            if (activeGuards.isEmpty()) {
                return;
            }
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                    .ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
            String actionText;
            if (action == com.fa26se040.icss.enums.AuditAction.CREATE) {
                actionText = "Đặt";
            } else if (action == com.fa26se040.icss.enums.AuditAction.UPDATE) {
                actionText = "Điều chỉnh";
            } else {
                actionText = "Huỷ";
            }
            String actorName = schedule.getCreatedBy() != null ? schedule.getCreatedBy().getFullName() : "Facility Manager";
            String msg = String.format("Khu vực %s: %s lịch sự kiện từ %s đến %s bởi %s.",
                    area.getName(), actionText, dtf.format(schedule.getStartAt()), dtf.format(schedule.getEndAt()), actorName);
            notifService.createForUsers(
                    activeGuards,
                    com.fa26se040.icss.enums.NotificationType.EVENT_MODE_SCHEDULED,
                    "Lịch chế độ sự kiện khu vực",
                    msg,
                    area.getId(),
                    "AREA"
            );
        });
    }

    private void sendFmScheduleFailedNotification(Area area, com.fa26se040.icss.entity.AreaEventSchedule schedule, String failReason) {
        executeAfterCommitOrImmediately(() -> {
            InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();
            if (notifService == null || schedule.getCreatedBy() == null) {
                return;
            }
            notifService.createForUsers(
                    List.of(schedule.getCreatedBy()),
                    com.fa26se040.icss.enums.NotificationType.EVENT_MODE_SCHEDULE_FAILED,
                    "Kích hoạt lịch sự kiện thất bại",
                    String.format("Lịch sự kiện tại khu vực %s không thể kích hoạt do: %s.", area.getName(), failReason),
                    area.getId(),
                    "AREA"
            );
        });
    }

    private void sendFmScheduleStartingNotification(com.fa26se040.icss.entity.AreaEventSchedule schedule) {
        executeAfterCommitOrImmediately(() -> {
            InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();
            if (notifService == null || schedule.getCreatedBy() == null) {
                return;
            }
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                    .ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
            notifService.createForUsers(
                    List.of(schedule.getCreatedBy()),
                    com.fa26se040.icss.enums.NotificationType.EVENT_MODE_SCHEDULE_STARTING,
                    "Lịch chế độ sự kiện sắp diễn ra",
                    String.format("Lịch sự kiện tại khu vực %s sẽ bắt đầu lúc %s. Vui lòng kiểm tra chuẩn bị.",
                            schedule.getArea().getName(), dtf.format(schedule.getStartAt())),
                    schedule.getArea().getId(),
                    "AREA"
            );
        });
    }

    @Transactional
    public com.fa26se040.icss.dto.area.EventScheduleResponse createSchedule(
            UUID areaId,
            com.fa26se040.icss.dto.area.EventScheduleRequest req,
            String actorEmail
    ) {
        Area area = areaRepository.findByIdWithLock(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        OffsetDateTime now = OffsetDateTime.now();
        AreaRowState before = AreaRowState.of(area);
        cleanupExpiredSessions(area, now);

        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }
        if (area.getAreaLevel() != AreaLevel.INTERNAL_CONFIDENTIAL
                && area.getAreaLevel() != AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_022);
        }

        if (req == null || req.startAt() == null || !req.startAt().isAfter(now)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_032);
        }
        int maxLeadDays = getEventModeScheduleMaxLeadDays();
        if (req.startAt().isAfter(now.plusDays(maxLeadDays))) {
            throw new AreaException(AreaErrorCode.ERR_AREA_033, maxLeadDays);
        }
        if (req.endAt() == null || !req.endAt().isAfter(req.startAt())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_034);
        }
        long durationMinutes = java.time.Duration.between(req.startAt(), req.endAt()).toMinutes();
        int minMinutes = getEventModeMinMinutes();
        if (durationMinutes < minMinutes) {
            throw new AreaException(AreaErrorCode.ERR_AREA_035, minMinutes);
        }
        int maxHours = getEventModeMaxHours();
        if (durationMinutes > maxHours * 60L) {
            throw new AreaException(AreaErrorCode.ERR_AREA_036, maxHours);
        }

        String rawNote = req.note();
        if (rawNote == null || rawNote.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String trimmedNote = rawNote.trim();
        if (trimmedNote.length() < 10 || trimmedNote.length() > 500) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }

        String rawReasonCode = req.reasonCode();
        if (rawReasonCode == null || rawReasonCode.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String normReasonCode = rawReasonCode.trim().toUpperCase();
        // BR-ES-L2: lịch chỉ nhận lý do của nhóm lịch tương ứng
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = findReasonInGroup("EVENT_SCHEDULE_CREATE", normReasonCode);
        String reasonLabel = reasonItem.getLabel();

        List<com.fa26se040.icss.entity.AreaEventSchedule> scheduledSchedules = eventScheduleRepository
                .findByAreaIdAndStatus(areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        for (com.fa26se040.icss.entity.AreaEventSchedule s : scheduledSchedules) {
            if (req.startAt().isBefore(s.getEndAt()) && req.endAt().isAfter(s.getStartAt())) {
                throw new AreaException(AreaErrorCode.ERR_AREA_037);
            }
        }
        if (area.isEventActive(now) && area.getOpenUntil() != null) {
            if (req.startAt().isBefore(area.getOpenUntil()) && req.endAt().isAfter(now)) {
                throw new AreaException(AreaErrorCode.ERR_AREA_038);
            }
        }

        int maxSchedules = getEventModeMaxSchedulesPerArea();
        if (scheduledSchedules.size() >= maxSchedules) {
            throw new AreaException(AreaErrorCode.ERR_AREA_039, maxSchedules);
        }

        validateEventBudget(areaId, new com.fa26se040.icss.dto.area.TimeInterval(req.startAt(), req.endAt()), null);

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        com.fa26se040.icss.entity.AreaEventSchedule schedule = com.fa26se040.icss.entity.AreaEventSchedule.builder()
                .area(area)
                .startAt(req.startAt())
                .endAt(req.endAt())
                .status(com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(normReasonCode)
                .reasonLabel(reasonLabel)
                .note(trimmedNote)
                .createdBy(actor)
                .createdAt(now)
                .build();
        com.fa26se040.icss.entity.AreaEventSchedule saved = eventScheduleRepository.save(schedule);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot snapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                        saved.getId(),
                        saved.getStartAt(),
                        saved.getEndAt(),
                        saved.getStatus().name(),
                        saved.getReasonCode(),
                        saved.getReasonLabel(),
                        saved.getNote()
                );
        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_SCHEDULE,
                com.fa26se040.icss.enums.AuditAction.CREATE,
                saved.getId().toString(),
                area,
                null,
                null,
                snapshot,
                trimmedNote,
                actor
        );

        bumpVersionIfChanged(area, before);
        sendGuardScheduleNotification(area, com.fa26se040.icss.enums.AuditAction.CREATE, saved);

        return mapToEventScheduleResponse(saved);
    }

    @Transactional
    public com.fa26se040.icss.dto.area.EventScheduleResponse updateSchedule(
            UUID areaId,
            UUID scheduleId,
            com.fa26se040.icss.dto.area.EventScheduleRequest req,
            String actorEmail
    ) {
        Area area = areaRepository.findByIdWithLock(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        OffsetDateTime now = OffsetDateTime.now();
        AreaRowState before = AreaRowState.of(area);
        cleanupExpiredSessions(area, now);

        com.fa26se040.icss.entity.AreaEventSchedule schedule = eventScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_043));
        if (!schedule.getArea().getId().equals(areaId)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_043);
        }
        if (schedule.getStatus() != com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_040);
        }

        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }
        if (area.getAreaLevel() != AreaLevel.INTERNAL_CONFIDENTIAL
                && area.getAreaLevel() != AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_022);
        }

        if (req == null || req.startAt() == null || !req.startAt().isAfter(now)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_032);
        }
        int maxLeadDays = getEventModeScheduleMaxLeadDays();
        if (req.startAt().isAfter(now.plusDays(maxLeadDays))) {
            throw new AreaException(AreaErrorCode.ERR_AREA_033, maxLeadDays);
        }
        if (req.endAt() == null || !req.endAt().isAfter(req.startAt())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_034);
        }
        long durationMinutes = java.time.Duration.between(req.startAt(), req.endAt()).toMinutes();
        int minMinutes = getEventModeMinMinutes();
        if (durationMinutes < minMinutes) {
            throw new AreaException(AreaErrorCode.ERR_AREA_035, minMinutes);
        }
        int maxHours = getEventModeMaxHours();
        if (durationMinutes > maxHours * 60L) {
            throw new AreaException(AreaErrorCode.ERR_AREA_036, maxHours);
        }

        String rawNote = req.note();
        if (rawNote == null || rawNote.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String trimmedNote = rawNote.trim();
        if (trimmedNote.length() < 10 || trimmedNote.length() > 500) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }

        String rawReasonCode = req.reasonCode();
        if (rawReasonCode == null || rawReasonCode.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String normReasonCode = rawReasonCode.trim().toUpperCase();
        // BR-ES-L2: lịch chỉ nhận lý do của nhóm lịch tương ứng
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = findReasonInGroup("EVENT_SCHEDULE_UPDATE", normReasonCode);
        String reasonLabel = reasonItem.getLabel();

        List<com.fa26se040.icss.entity.AreaEventSchedule> scheduledSchedules = eventScheduleRepository
                .findByAreaIdAndStatus(areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        for (com.fa26se040.icss.entity.AreaEventSchedule s : scheduledSchedules) {
            if (s.getId().equals(scheduleId)) {
                continue;
            }
            if (req.startAt().isBefore(s.getEndAt()) && req.endAt().isAfter(s.getStartAt())) {
                throw new AreaException(AreaErrorCode.ERR_AREA_037);
            }
        }
        if (area.isEventActive(now) && area.getOpenUntil() != null) {
            if (req.startAt().isBefore(area.getOpenUntil()) && req.endAt().isAfter(now)) {
                throw new AreaException(AreaErrorCode.ERR_AREA_038);
            }
        }

        validateEventBudget(areaId, new com.fa26se040.icss.dto.area.TimeInterval(req.startAt(), req.endAt()), scheduleId);

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                        schedule.getId(),
                        schedule.getStartAt(),
                        schedule.getEndAt(),
                        schedule.getStatus().name(),
                        schedule.getReasonCode(),
                        schedule.getReasonLabel(),
                        schedule.getNote()
                );

        schedule.setStartAt(req.startAt());
        schedule.setEndAt(req.endAt());
        // KHÔNG gán reasonCode/reasonLabel/note của request vào schedule để giữ lý do gốc lúc đặt (R2)
        schedule.setUpdatedBy(actor);
        schedule.setUpdatedAt(now);
        com.fa26se040.icss.entity.AreaEventSchedule saved = eventScheduleRepository.save(schedule);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                        saved.getId(),
                        saved.getStartAt(),
                        saved.getEndAt(),
                        saved.getStatus().name(),
                        normReasonCode,
                        reasonLabel,
                        trimmedNote
                );

        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_SCHEDULE,
                com.fa26se040.icss.enums.AuditAction.UPDATE,
                saved.getId().toString(),
                area,
                null,
                oldSnapshot,
                newSnapshot,
                trimmedNote,
                actor
        );

        bumpVersionIfChanged(area, before);
        sendGuardScheduleNotification(area, com.fa26se040.icss.enums.AuditAction.UPDATE, saved);

        return mapToEventScheduleResponse(saved);
    }

    @Transactional
    public com.fa26se040.icss.dto.area.EventScheduleResponse cancelSchedule(
            UUID areaId,
            UUID scheduleId,
            com.fa26se040.icss.dto.area.EventScheduleCancelRequest req,
            String actorEmail
    ) {
        Area area = areaRepository.findByIdWithLock(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        OffsetDateTime now = OffsetDateTime.now();
        AreaRowState before = AreaRowState.of(area);
        cleanupExpiredSessions(area, now);

        com.fa26se040.icss.entity.AreaEventSchedule schedule = eventScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_043));
        if (!schedule.getArea().getId().equals(areaId)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_043);
        }
        if (schedule.getStatus() != com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_040);
        }

        if (req == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String rawNote = req.note();
        if (rawNote == null || rawNote.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String trimmedNote = rawNote.trim();
        if (trimmedNote.length() < 10 || trimmedNote.length() > 500) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }

        String rawReasonCode = req.reasonCode();
        if (rawReasonCode == null || rawReasonCode.trim().isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }
        String normReasonCode = rawReasonCode.trim().toUpperCase();
        // BR-ES-L2: lịch chỉ nhận lý do của nhóm lịch tương ứng
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = findReasonInGroup("EVENT_SCHEDULE_CANCEL", normReasonCode);
        String reasonLabel = reasonItem.getLabel();

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                        schedule.getId(),
                        schedule.getStartAt(),
                        schedule.getEndAt(),
                        schedule.getStatus().name(),
                        schedule.getReasonCode(),
                        schedule.getReasonLabel(),
                        schedule.getNote()
                );

        schedule.setStatus(com.fa26se040.icss.enums.AreaEventScheduleStatus.CANCELLED);
        schedule.setCancelledBy(actor);
        schedule.setCancelledAt(now);
        schedule.setCancelReasonCode(normReasonCode);
        schedule.setCancelReasonLabel(reasonLabel);
        schedule.setCancelNote(trimmedNote);
        com.fa26se040.icss.entity.AreaEventSchedule saved = eventScheduleRepository.save(schedule);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                        saved.getId(),
                        saved.getStartAt(),
                        saved.getEndAt(),
                        saved.getStatus().name(),
                        normReasonCode,
                        reasonLabel,
                        trimmedNote
                );

        auditService.record(
                com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_SCHEDULE,
                com.fa26se040.icss.enums.AuditAction.CANCEL,
                saved.getId().toString(),
                area,
                null,
                oldSnapshot,
                newSnapshot,
                trimmedNote,
                actor
        );

        bumpVersionIfChanged(area, before);
        sendGuardScheduleNotification(area, com.fa26se040.icss.enums.AuditAction.CANCEL, saved);

        return mapToEventScheduleResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<com.fa26se040.icss.dto.area.EventScheduleResponse> getSchedules(UUID areaId, String status) {
        areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        List<com.fa26se040.icss.entity.AreaEventSchedule> list;
        if (status != null && !status.trim().isEmpty()) {
            com.fa26se040.icss.enums.AreaEventScheduleStatus scheduleStatus;
            try {
                scheduleStatus = com.fa26se040.icss.enums.AreaEventScheduleStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return List.of();
            }
            list = eventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(areaId, scheduleStatus);
        } else {
            list = eventScheduleRepository.findByAreaIdOrderByStartAtAsc(areaId);
        }
        return list.stream().map(this::mapToEventScheduleResponse).toList();
    }

    private com.fa26se040.icss.dto.area.EventScheduleResponse mapToEventScheduleResponse(com.fa26se040.icss.entity.AreaEventSchedule s) {
        return new com.fa26se040.icss.dto.area.EventScheduleResponse(
                s.getId(),
                s.getArea().getId(),
                s.getStartAt(),
                s.getEndAt(),
                s.getStatus().name(),
                s.getReasonCode(),
                s.getReasonLabel(),
                s.getNote(),
                s.getCreatedBy() != null ? s.getCreatedBy().getId() : null,
                s.getCreatedBy() != null ? s.getCreatedBy().getFullName() : null,
                s.getCreatedAt(),
                s.getUpdatedBy() != null ? s.getUpdatedBy().getFullName() : null,
                s.getUpdatedAt(),
                s.getCancelledBy() != null ? s.getCancelledBy().getFullName() : null,
                s.getCancelledAt(),
                s.getCancelReasonCode(),
                s.getCancelReasonLabel(),
                s.getCancelNote(),
                s.getFailedAt(),
                s.getFailReason(),
                s.getSession() != null ? s.getSession().getId() : null
        );
    }

    public void processExpiredEventSessions(OffsetDateTime now) {
        List<UUID> areaIds = eventSessionRepository.findDistinctAreaIdsWithExpiredActiveSessions(now);
        for (UUID areaId : areaIds) {
            try {
                org.springframework.transaction.support.TransactionTemplate tx =
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> expireEventSessionForAreaInTx(areaId, now));
            } catch (Exception ex) {
                log.error("Failed to expire event session for area {}: {}", areaId, ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void expireEventSessionForAreaInTx(UUID areaId, OffsetDateTime now) {
        Area area = areaRepository.findByIdWithLock(areaId).orElse(null);
        if (area == null) {
            return;
        }
        AreaRowState before = AreaRowState.of(area);
        cleanupExpiredSessions(area, now);
        bumpVersionIfChanged(area, before);
    }

    public void processScheduledEventActivations(OffsetDateTime now) {
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedules = eventScheduleRepository
                .findSchedulesReadyToStart(com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED, now);
        for (com.fa26se040.icss.entity.AreaEventSchedule schedule : schedules) {
            try {
                org.springframework.transaction.support.TransactionTemplate tx =
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> activateScheduleInTx(schedule.getId(), now));
            } catch (Exception ex) {
                log.error("Failed to process schedule activation for schedule {}: {}", schedule.getId(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void activateScheduleInTx(UUID scheduleId, OffsetDateTime now) {
        com.fa26se040.icss.context.AuditContext.runAsSystem("EVENT_SCHEDULE_ACTIVATION", () -> {
            com.fa26se040.icss.entity.AreaEventSchedule schedule = eventScheduleRepository.findById(scheduleId).orElse(null);
            if (schedule == null || schedule.getStatus() != com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED) {
                return;
            }
            Area area = areaRepository.findByIdWithLock(schedule.getArea().getId()).orElse(null);
            if (area == null) {
                return;
            }
            AreaRowState before = AreaRowState.of(area);

            // Đóng các phiên hết hạn của khu vực trước khi tạo phiên mới (DF-U4)
            cleanupExpiredSessions(area, now);

            boolean isMissed = !schedule.getEndAt().isAfter(now);
            boolean isAreaActive = Boolean.TRUE.equals(area.getIsActive()) && area.getDeletedAt() == null;
            boolean isLevelAllowed = area.getAreaLevel() == AreaLevel.INTERNAL_CONFIDENTIAL
                    || area.getAreaLevel() == AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED;

            if (isMissed || !isAreaActive || !isLevelAllowed) {
                String failReason;
                if (isMissed) {
                    failReason = "Lịch sự kiện bị lỡ thời gian kết thúc";
                } else if (!isAreaActive) {
                    failReason = "Khu vực đã ngừng hoạt động hoặc đã bị xoá";
                } else {
                    failReason = "Cấp độ khu vực không còn phù hợp cho chế độ sự kiện";
                }

                schedule.setStatus(com.fa26se040.icss.enums.AreaEventScheduleStatus.FAILED);
                schedule.setFailedAt(now);
                schedule.setFailReason(failReason);
                eventScheduleRepository.save(schedule);

                com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot snap =
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot(
                                schedule.getId(),
                                schedule.getStartAt(),
                                schedule.getEndAt(),
                                schedule.getStatus().name(),
                                schedule.getReasonCode(),
                                schedule.getReasonLabel(),
                                schedule.getNote(),
                                failReason
                        );

                auditService.record(
                        com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_SCHEDULE,
                        com.fa26se040.icss.enums.AuditAction.FAIL,
                        schedule.getId().toString(),
                        area,
                        null,
                        null,
                        snap,
                        failReason,
                        com.fa26se040.icss.dto.audit.AuditActor.system("EVENT_SCHEDULE_ACTIVATION")
                );

                sendFmScheduleFailedNotification(area, schedule, failReason);
                // Dọn phiên hết hạn ở trên có thể đã đổi dòng areas
                bumpVersionIfChanged(area, before);
            } else {
                com.fa26se040.icss.entity.AreaEventSession session = com.fa26se040.icss.entity.AreaEventSession.builder()
                        .area(area)
                        .startedAt(schedule.getStartAt())
                        .plannedEnd(schedule.getEndAt())
                        .actualEnd(null)
                        .startedBy(schedule.getCreatedBy())
                        .createdAt(now)
                        .scheduleId(schedule.getId())
                        .build();
                eventSessionRepository.save(session);

                area.setOpenToMembers(true);
                area.setOpenUntil(schedule.getEndAt());
                bumpVersionIfChanged(area, before);
                areaRepository.save(area);

                schedule.setStatus(com.fa26se040.icss.enums.AreaEventScheduleStatus.STARTED);
                schedule.setSession(session);
                eventScheduleRepository.save(schedule);

                com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot newSnapshot =
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(
                                true,
                                schedule.getEndAt(),
                                schedule.getReasonCode(),
                                schedule.getReasonLabel(),
                                schedule.getNote(),
                                session.getId(),
                                schedule.getEndAt(),
                                schedule.getId()
                        );

                auditService.record(
                        com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_MODE,
                        com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE,
                        area.getId().toString(),
                        area,
                        null,
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(false, null),
                        newSnapshot,
                        schedule.getNote(),
                        com.fa26se040.icss.dto.audit.AuditActor.system("EVENT_SCHEDULE_ACTIVATION")
                );

                sendGuardEventModeChangedNotification(area, com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE, schedule.getCreatedBy(), schedule.getEndAt(), true);
            }
        });
    }

    public void processScheduleReminders(OffsetDateTime now) {
        int reminderMinutes = getEventModeScheduleReminderMinutes();
        if (reminderMinutes <= 0) {
            return;
        }
        OffsetDateTime threshold = now.plusMinutes(reminderMinutes);
        List<com.fa26se040.icss.entity.AreaEventSchedule> schedules = eventScheduleRepository.findSchedulesToRemind(
                com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED, now, threshold);
        for (com.fa26se040.icss.entity.AreaEventSchedule s : schedules) {
            try {
                org.springframework.transaction.support.TransactionTemplate tx =
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> remindScheduleInTx(s.getId(), now));
            } catch (Exception ex) {
                log.error("Failed to remind schedule {}: {}", s.getId(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void remindScheduleInTx(UUID scheduleId, OffsetDateTime now) {
        com.fa26se040.icss.entity.AreaEventSchedule schedule = eventScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.getRemindedAt() != null || schedule.getStatus() != com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED) {
            return;
        }
        schedule.setRemindedAt(now);
        eventScheduleRepository.save(schedule);

        sendFmScheduleStartingNotification(schedule);
    }
}
