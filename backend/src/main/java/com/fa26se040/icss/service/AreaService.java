package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.dto.area.AreaGeometryResponse;
import com.fa26se040.icss.dto.area.AreaListItemResponse;
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
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot;
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
    private final AccessControlAuditService auditService;
    private final com.fa26se040.icss.repository.ReasonCatalogRepository reasonCatalogRepository;
    private final com.fa26se040.icss.repository.AreaEventSessionRepository eventSessionRepository;
    private final SystemConfigService systemConfigService;
    private final org.springframework.beans.factory.ObjectProvider<InAppNotificationService> inAppNotificationServiceProvider;

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
                .isActive(true)
                .build();

        try {
            Area savedArea = areaRepository.saveAndFlush(area);
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
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

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

        if (req.getAreaLevel() == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_003);
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

        try {
            Area savedArea = areaRepository.saveAndFlush(area);
            return mapToAreaResponse(savedArea);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Data integrity violation on updating area [{}]: {}", name, ex.getMessage());
            String msg = (ex.getMessage() + " " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
            if (msg.contains("ux_areas_floor_name_active")) {
                throw new AreaException(AreaErrorCode.ERR_AREA_020);
            }
            throw ex;
        }
    }

    @Transactional
    public AreaGeometryResponse saveGeometry(UUID id, AreaGeometry geometry, String actorEmail) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        List<Area> existingOnFloor = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(
                area.getBuilding(),
                area.getFloor()
        );

        geometryValidator.validate(geometry, area.getId(), area.getBuilding(), area.getFloor(), existingOnFloor);

        geometry.setType("polygon");
        geometry.setVersion(1);

        resolveActorId(actorEmail);

        area.setGeometry(geometry);

        Area savedArea = areaRepository.save(area);
        return new AreaGeometryResponse(
                savedArea.getId(),
                savedArea.getName(),
                savedArea.getAreaLevel(),
                savedArea.getIsActive(),
                savedArea.getGeometry()
        );
    }

    @Transactional
    public void deleteGeometry(UUID id, String actorEmail) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        if (area.getGeometry() == null) {
            return;
        }

        resolveActorId(actorEmail);

        area.setGeometry(null);
        areaRepository.save(area);
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
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        AreaDependencyResponse dep = dependencyChecker.check(id);
        if (!dep.canDeactivate()) {
            AreaDependencyResponse.Blocker firstBlocker = dep.blockers().get(0);
            throw new AreaException(firstBlocker.errorCode(), firstBlocker.count());
        }

        resolveActorId(actorEmail);

        area.setIsActive(false);
        area.setDeletedAt(OffsetDateTime.now());

        areaRepository.save(area);
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
        Area area = areaRepository.findById(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

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
                    com.fa26se040.icss.enums.AccessControlTargetType.AREA_ACCESS_RULES,
                    com.fa26se040.icss.enums.AccessControlAction.UPDATE,
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

    public java.util.List<Area> findAreasViolatingNewEventLimits(int newMaxHours, int newWindowDays, int newBudgetHours) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Area> allAreas = areaRepository.findAll();
        List<Area> violating = new java.util.ArrayList<>();

        for (Area area : allAreas) {
            if (!area.isEventActive(now)) {
                continue;
            }
            OffsetDateTime openUntil = area.getOpenUntil();
            if (openUntil == null) {
                continue;
            }

            // (a) open_until − now > MAX_HOURS mới
            if (openUntil.isAfter(now.plusHours(newMaxHours))) {
                violating.add(area);
                continue;
            }

            // (b) giờ đã dùng trong [open_until − WINDOW_DAYS mới, open_until] (tính như calculateUsedHoursInWindow, phiên đang mở tính đến planned_end) > BUDGET_HOURS mới
            OffsetDateTime windowStart = openUntil.minusDays(newWindowDays);
            OffsetDateTime windowEnd = openUntil;
            double used = calculateUsedHoursInWindow(area.getId(), windowStart, windowEnd);

            if (used > newBudgetHours + 1e-4) {
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

        List<com.fa26se040.icss.entity.AreaEventSession> activeSessions = eventSessionRepository.findAll().stream()
                .filter(s -> s.getActualEnd() == null
                        && s.getPlannedEnd().isAfter(now)
                        && !s.getPlannedEnd().isAfter(reminderThreshold)
                        && s.getExpiryRemindedAt() == null)
                .toList();

        if (activeSessions.isEmpty()) {
            return 0;
        }

        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        InAppNotificationService notifService = inAppNotificationServiceProvider.getIfAvailable();

        int count = 0;
        for (com.fa26se040.icss.entity.AreaEventSession session : activeSessions) {
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
                    String areaName = session.getArea() != null ? session.getArea().getName() : "Khu vực";
                    String plannedStr = dtf.format(session.getPlannedEnd());
                    notifService.createForUsers(
                            recipients,
                            com.fa26se040.icss.enums.NotificationType.EVENT_MODE_EXPIRING,
                            "Chế độ sự kiện sắp hết hạn",
                            String.format("Chế độ sự kiện tại khu vực %s sẽ kết thúc lúc %s. Vui lòng kiểm tra hoặc gia hạn nếu cần.", areaName, plannedStr),
                            session.getArea() != null ? session.getArea().getId() : null,
                            "AREA"
                    );
                }
            }
        }
        return count;
    }

    private String buildEventModeStatusChangedMessage(Area area, OffsetDateTime lastPlannedEnd) {
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        OffsetDateTime now = OffsetDateTime.now();
        if (area != null && area.isEventActive(now)) {
            return "Trạng thái sự kiện đã thay đổi: đang mở đến " + dtf.format(area.getOpenUntil()) + ". Vui lòng tải lại trang.";
        }
        if (lastPlannedEnd != null) {
            return "Trạng thái sự kiện đã thay đổi: đã kết thúc lúc " + dtf.format(lastPlannedEnd) + ". Vui lòng tải lại trang.";
        }
        if (area != null && area.getOpenUntil() != null) {
            return "Trạng thái sự kiện đã thay đổi: đã kết thúc lúc " + dtf.format(area.getOpenUntil()) + ". Vui lòng tải lại trang.";
        }
        return "Trạng thái sự kiện đã thay đổi: đang tắt. Vui lòng tải lại trang.";
    }

    private void sendGuardEventModeChangedNotification(Area area, com.fa26se040.icss.enums.AccessControlAction action, User actor, OffsetDateTime openUntil) {
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
        if (action == com.fa26se040.icss.enums.AccessControlAction.ENABLE_EVENT_MODE) {
            actionText = "Bật";
        } else if (action == com.fa26se040.icss.enums.AccessControlAction.EXTEND_EVENT_MODE) {
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
    }

    @Transactional
    public AreaResponse updateEventMode(UUID id, AreaEventModeUpdateRequest req, String actorEmail) {
        log.info("Updating event mode for area {}: enabled={}, openUntil={}, reasonCode={}",
                id, req != null ? req.enabled() : null, req != null ? req.openUntil() : null, req != null ? req.reasonCode() : null);

        // 1. Khoá area (B2). Không tồn tại -> lỗi hiện có (ERR_AREA_002)
        Area area = areaRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        OffsetDateTime now = OffsetDateTime.now();

        // 2. Dọn dữ liệu sót (BR-EV-06): đóng mọi phiên actual_end IS NULL ∧ planned_end <= now với actual_end = planned_end, ended_by = NULL
        List<com.fa26se040.icss.entity.AreaEventSession> expiredSessions =
                eventSessionRepository.findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(id, now);
        for (com.fa26se040.icss.entity.AreaEventSession exp : expiredSessions) {
            exp.setActualEnd(exp.getPlannedEnd());
            eventSessionRepository.save(exp);
        }
        if (Boolean.TRUE.equals(area.getOpenToMembers()) && !area.isEventActive(now)) {
            area.setOpenToMembers(false);
            area.setOpenUntil(null);
            area = areaRepository.save(area);
        }

        // 3. Validate đầu vào:
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
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository.findByCode(normReasonCode).orElse(null);
        if (reasonItem == null || !Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        String reasonLabel = reasonItem.getLabel();

        // 4. activeNow = hàm B1 trên trạng thái sau bước 2
        boolean activeNow = area.isEventActive(now);
        boolean targetEnabled = Boolean.TRUE.equals(req.enabled());
        boolean oldEnabled = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime oldOpenUntil = area.getOpenUntil();

        // 5. Xác định thao tác (BR-EV-08):
        String expectedActionType;
        com.fa26se040.icss.enums.AccessControlAction action;
        if (!activeNow && targetEnabled) {
            expectedActionType = "EVENT_ENABLE";
            action = com.fa26se040.icss.enums.AccessControlAction.ENABLE_EVENT_MODE;
        } else if (activeNow && targetEnabled) {
            expectedActionType = "EVENT_EXTEND";
            action = com.fa26se040.icss.enums.AccessControlAction.EXTEND_EVENT_MODE;
        } else if (activeNow && !targetEnabled) {
            expectedActionType = "EVENT_DISABLE";
            action = com.fa26se040.icss.enums.AccessControlAction.DISABLE_EVENT_MODE;
        } else {
            // 6. (BR-EV-10) !activeNow ∧ !enabled -> 409 mã M1 (ERR_AREA_030), KHÔNG audit
            OffsetDateTime lastPlannedEnd = eventSessionRepository.findTopByAreaIdOrderByStartedAtDesc(id)
                    .map(com.fa26se040.icss.entity.AreaEventSession::getPlannedEnd)
                    .orElse(oldOpenUntil);
            String statusMsg = buildEventModeStatusChangedMessage(area, lastPlannedEnd);
            throw new AreaException(AreaErrorCode.ERR_AREA_030, statusMsg);
        }

        // 7. (BR-EV-09) reason.actionType
        if (!"EVENT_ENABLE".equalsIgnoreCase(reasonItem.getActionType())
                && !"EVENT_EXTEND".equalsIgnoreCase(reasonItem.getActionType())
                && !"EVENT_DISABLE".equalsIgnoreCase(reasonItem.getActionType())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_026);
        }
        if (!expectedActionType.equalsIgnoreCase(reasonItem.getActionType())) {
            OffsetDateTime lastPlannedEnd = eventSessionRepository.findTopByAreaIdOrderByStartedAtDesc(id)
                    .map(com.fa26se040.icss.entity.AreaEventSession::getPlannedEnd)
                    .orElse(oldOpenUntil);
            String statusMsg = buildEventModeStatusChangedMessage(area, lastPlannedEnd);
            throw new AreaException(AreaErrorCode.ERR_AREA_030, statusMsg);
        }

        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();

        // 8. (BR-EV-11) EXTEND với openUntil lệch giờ hiện tại < 1 giây -> trả về thành công, không đổi, không audit, không thông báo
        if (expectedActionType.equals("EVENT_EXTEND")
                && req.openUntil() != null
                && oldOpenUntil != null
                && Math.abs(java.time.Duration.between(req.openUntil(), oldOpenUntil).toMillis()) < 1000) {
            log.info("Area {} extend event mode unchanged (<1s diff), skipping audit and update", id);
            return mapToAreaResponse(area, computeDiffersFromPreset(area, presetMap));
        }

        // 9. (BR-EV-03) ENABLE / EXTEND trên khu vực isActive = false -> từ chối (ERR_AREA_017). DISABLE vẫn cho phép.
        if ((expectedActionType.equals("EVENT_ENABLE") || expectedActionType.equals("EVENT_EXTEND"))
                && (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        // 10. Loại khu vực (ERR_AREA_022), openUntil > now (ERR_AREA_023), min minutes (M2: ERR_AREA_031), MAX_HOURS (ERR_AREA_027), ngân sách (ERR_AREA_028)
        if (area.getAreaLevel() != AreaLevel.INTERNAL_CONFIDENTIAL
                && area.getAreaLevel() != AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_022);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        if (targetEnabled) {
            OffsetDateTime targetOpenUntil = req.openUntil();
            if (targetOpenUntil == null || !targetOpenUntil.isAfter(now)) {
                throw new AreaException(AreaErrorCode.ERR_AREA_023);
            }

            int minMinutes = getEventModeMinMinutes();
            long reqMinutes = java.time.Duration.between(now, targetOpenUntil).toMinutes();
            if (reqMinutes < minMinutes) {
                throw new AreaException(AreaErrorCode.ERR_AREA_031,
                        "Thời lượng mở sự kiện tối thiểu là " + minMinutes + " phút.", minMinutes);
            }

            int maxHours = getEventModeMaxHours();
            if (targetOpenUntil.isAfter(now.plusHours(maxHours))) {
                throw new AreaException(AreaErrorCode.ERR_AREA_027,
                        "Thời gian mở sự kiện vượt quá giới hạn tối đa cho một phiên (" + maxHours + " giờ)");
            }

            // Đóng phiên hiện tại tại now TRƯỚC khi tính ngân sách
            if (expectedActionType.equals("EVENT_EXTEND")) {
                com.fa26se040.icss.entity.AreaEventSession currentSession = eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
                if (currentSession != null) {
                    currentSession.setActualEnd(now);
                    currentSession.setEndedBy(actor);
                    eventSessionRepository.save(currentSession);
                }
            }

            int windowDays = getEventModeWindowDays();
            int budgetHours = getEventModeBudgetHours();
            OffsetDateTime windowStart = targetOpenUntil.minusDays(windowDays);
            OffsetDateTime windowEnd = targetOpenUntil;
            double usedHours = calculateUsedHoursInWindow(id, windowStart, windowEnd);
            double requestedHours = reqMinutes / 60.0;

            if (usedHours + requestedHours > budgetHours + 1e-4) {
                double remainingHours = Math.max(0.0, budgetHours - usedHours);
                String msg = String.format(java.util.Locale.US,
                        "Vượt quá ngân sách thời gian mở sự kiện của khu vực. Đã dùng: %.1f giờ trong %d ngày gần nhất, còn lại: %.1f giờ, yêu cầu: %.1f giờ (ngân sách: %d giờ / %d ngày).",
                        usedHours, windowDays, remainingHours, requestedHours, budgetHours, windowDays);
                throw new AreaException(AreaErrorCode.ERR_AREA_028, msg);
            }

            com.fa26se040.icss.entity.AreaEventSession newSession = com.fa26se040.icss.entity.AreaEventSession.builder()
                    .area(area)
                    .startedAt(now)
                    .plannedEnd(targetOpenUntil)
                    .actualEnd(null)
                    .startedBy(actor)
                    .expiryRemindedAt(null)
                    .createdAt(now)
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
            }

            area.setOpenToMembers(false);
            area.setOpenUntil(null);
        }

        area.setUpdatedAt(now);
        Area savedArea = areaRepository.save(area);

        // 11. Ghi dữ liệu + 1 dòng audit AREA_EVENT_MODE + thông báo GUARD (B10)
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(oldEnabled, oldOpenUntil, null, null, null);
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(savedArea.getOpenToMembers(), savedArea.getOpenUntil(), normReasonCode, reasonLabel, trimmedNote);

        auditService.record(
                com.fa26se040.icss.enums.AccessControlTargetType.AREA_EVENT_MODE,
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

    private AreaResponse mapToAreaResponse(Area area) {
        return mapToAreaResponse(area, false);
    }

    private AreaResponse mapToAreaResponse(Area area, boolean differsFromPreset) {
        boolean openToMembers = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime openUntil = area.getOpenUntil();
        boolean eventActive = area.isEventActive(OffsetDateTime.now());

        return new AreaResponse(
                area.getId(),
                area.getName(),
                area.getAreaLevel(),
                area.getAreaAccessLevel(),
                area.getExplicitAuthorizationRequired(),
                area.getBuilding(),
                area.getFloor(),
                area.getGeometry(),
                area.getIsActive(),
                area.getCreatedAt(),
                area.getUpdatedAt(),
                differsFromPreset,
                openToMembers,
                openUntil,
                eventActive
        );
    }

    private AreaListItemResponse mapToAreaListItemResponse(Area area, boolean differsFromPreset) {
        boolean openToMembers = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime openUntil = area.getOpenUntil();
        boolean eventActive = area.isEventActive(OffsetDateTime.now());

        return new AreaListItemResponse(
                area.getId(),
                area.getName(),
                area.getAreaLevel(),
                area.getAreaAccessLevel(),
                area.getExplicitAuthorizationRequired(),
                area.getBuilding(),
                area.getFloor(),
                area.getIsActive(),
                area.getGeometry(),
                area.getGeometry() != null,
                differsFromPreset,
                openToMembers,
                openUntil,
                eventActive
        );
    }
}
