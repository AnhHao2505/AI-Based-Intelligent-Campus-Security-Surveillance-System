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
import java.util.List;
import java.util.Optional;
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

        List<CameraSimpleResponse> cameraResponses = area.getCameras().stream()
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

        List<Camera> camerasToAssign;
        if (cameraIds == null || cameraIds.isEmpty()) {
            camerasToAssign = List.of();
        } else {
            camerasToAssign = cameraRepository.findAllById(cameraIds);
            if (camerasToAssign.size() != cameraIds.size()) {
                throw new CameraException(CameraErrorCode.ERR_CAM_002);
            }
            boolean hasDecommissioned = camerasToAssign.stream()
                    .anyMatch(c -> c.getStatus() != CameraStatus.ACTIVE);
            if (hasDecommissioned) {
                throw new CameraException(CameraErrorCode.ERR_MAP_002);
            }
        }

        area.getCameras().clear();
        area.getCameras().addAll(camerasToAssign);
        Area savedArea = areaRepository.save(area);

        return getCamerasForArea(savedArea.getId());
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
        List<Area> activeAreas = areaRepository.findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(now);
        List<Area> violating = new java.util.ArrayList<>();

        for (Area area : activeAreas) {
            OffsetDateTime openUntil = area.getOpenUntil();
            if (openUntil == null || !openUntil.isAfter(now)) {
                continue;
            }

            if (openUntil.isAfter(now.plusHours(newMaxHours))) {
                violating.add(area);
                continue;
            }

            OffsetDateTime windowStart = openUntil.minusDays(newWindowDays);
            OffsetDateTime windowEnd = openUntil;
            double used = calculateUsedHoursInWindow(area.getId(), windowStart, windowEnd);
            double requested = java.time.Duration.between(now, openUntil).toMinutes() / 60.0;

            if (used + requested > newBudgetHours + 1e-4) {
                violating.add(area);
            }
        }
        return violating;
    }

    @Transactional
    public AreaResponse updateEventMode(UUID id, AreaEventModeUpdateRequest req, String actorEmail) {
        log.info("Updating event mode for area {}: enabled={}, openUntil={}, reasonCode={}",
                id, req != null ? req.enabled() : null, req != null ? req.openUntil() : null, req != null ? req.reasonCode() : null);

        if (req == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_024);
        }

        Area area = areaRepository.findById(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        if (area.getAreaLevel() != AreaLevel.INTERNAL_CONFIDENTIAL
                && area.getAreaLevel() != AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED) {
            throw new AreaException(AreaErrorCode.ERR_AREA_022);
        }

        boolean targetEnabled = Boolean.TRUE.equals(req.enabled());
        OffsetDateTime targetOpenUntil = targetEnabled ? req.openUntil() : null;

        boolean oldEnabled = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime oldOpenUntil = area.getOpenUntil();
        OffsetDateTime now = OffsetDateTime.now();

        if (targetEnabled && (targetOpenUntil == null || !targetOpenUntil.isAfter(now))) {
            throw new AreaException(AreaErrorCode.ERR_AREA_023);
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

        boolean unchanged;
        if (targetEnabled) {
            unchanged = oldEnabled && targetOpenUntil != null && oldOpenUntil != null
                    && Math.abs(java.time.Duration.between(targetOpenUntil, oldOpenUntil).toMillis()) < 1000;
        } else {
            unchanged = !oldEnabled && oldOpenUntil == null;
        }

        java.util.Map<AreaLevel, AreaLevelPreset> presetMap = loadPresetMap();

        if (unchanged) {
            log.info("Area {} event mode unchanged, skipping audit log and DB update", id);
            return mapToAreaResponse(area, computeDiffersFromPreset(area, presetMap));
        }

        String expectedActionType;
        com.fa26se040.icss.enums.AccessControlAction action;
        if (!oldEnabled && targetEnabled) {
            expectedActionType = "EVENT_ENABLE";
            action = com.fa26se040.icss.enums.AccessControlAction.ENABLE_EVENT_MODE;
        } else if (oldEnabled && !targetEnabled) {
            expectedActionType = "EVENT_DISABLE";
            action = com.fa26se040.icss.enums.AccessControlAction.DISABLE_EVENT_MODE;
        } else {
            expectedActionType = "EVENT_EXTEND";
            action = com.fa26se040.icss.enums.AccessControlAction.EXTEND_EVENT_MODE;
        }

        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository.findByCode(normReasonCode)
                .orElse(null);
        if (reasonItem == null || !Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        if (!expectedActionType.equalsIgnoreCase(reasonItem.getActionType())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_026);
        }
        String reasonLabel = reasonItem.getLabel();

        List<com.fa26se040.icss.entity.AreaEventSession> expiredSessions =
                eventSessionRepository.findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(id, now);
        for (com.fa26se040.icss.entity.AreaEventSession exp : expiredSessions) {
            exp.setActualEnd(exp.getPlannedEnd());
            eventSessionRepository.save(exp);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        if (targetEnabled) {
            int maxHours = getEventModeMaxHours();
            int windowDays = getEventModeWindowDays();
            int budgetHours = getEventModeBudgetHours();

            if (targetOpenUntil.isAfter(now.plusHours(maxHours))) {
                throw new AreaException(AreaErrorCode.ERR_AREA_027,
                        "Thời gian mở sự kiện vượt quá giới hạn tối đa cho một phiên (" + maxHours + " giờ)");
            }

            if (expectedActionType.equals("EVENT_EXTEND")) {
                com.fa26se040.icss.entity.AreaEventSession currentSession =
                        eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
                if (currentSession != null) {
                    currentSession.setActualEnd(now);
                    currentSession.setEndedBy(actor);
                    eventSessionRepository.save(currentSession);
                }
            }

            OffsetDateTime windowStart = targetOpenUntil.minusDays(windowDays);
            OffsetDateTime windowEnd = targetOpenUntil;
            double usedHours = calculateUsedHoursInWindow(id, windowStart, windowEnd);
            double requestedHours = java.time.Duration.between(now, targetOpenUntil).toMinutes() / 60.0;

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
                    .createdAt(now)
                    .build();
            eventSessionRepository.save(newSession);
        } else {
            com.fa26se040.icss.entity.AreaEventSession currentSession =
                    eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
            if (currentSession != null) {
                currentSession.setActualEnd(now);
                currentSession.setEndedBy(actor);
                eventSessionRepository.save(currentSession);
            }
        }

        area.setOpenToMembers(targetEnabled);
        area.setOpenUntil(targetOpenUntil);
        area.setUpdatedAt(now);

        Area savedArea = areaRepository.save(area);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot oldSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(oldEnabled, oldOpenUntil, null, null, null);
        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot newSnapshot =
                new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(targetEnabled, targetOpenUntil, normReasonCode, reasonLabel, trimmedNote);

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

        return mapToAreaResponse(savedArea, computeDiffersFromPreset(savedArea, presetMap));
    }

    private AreaResponse mapToAreaResponse(Area area) {
        return mapToAreaResponse(area, false);
    }

    private AreaResponse mapToAreaResponse(Area area, boolean differsFromPreset) {
        boolean openToMembers = Boolean.TRUE.equals(area.getOpenToMembers());
        OffsetDateTime openUntil = area.getOpenUntil();
        boolean eventActive = openToMembers && openUntil != null && OffsetDateTime.now().isBefore(openUntil);

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
        boolean eventActive = openToMembers && openUntil != null && OffsetDateTime.now().isBefore(openUntil);

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
