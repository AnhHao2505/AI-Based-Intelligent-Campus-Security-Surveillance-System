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
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

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

        if (req.getAreaLevel() == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_003);
        }

        boolean wasInternalOrContact = area.getAreaLevel() == AreaLevel.INTERNAL_CONFIDENTIAL
                || area.getAreaLevel() == AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED;
        boolean willBeInternalOrContact = req.getAreaLevel() == AreaLevel.INTERNAL_CONFIDENTIAL
                || req.getAreaLevel() == AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED;
        if (wasInternalOrContact && !willBeInternalOrContact) {
            List<com.fa26se040.icss.entity.AreaEventSchedule> pendingSchedules = (eventScheduleRepository != null && id != null)
                    ? eventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(id, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                    : Collections.emptyList();
            if (!pendingSchedules.isEmpty()) {
                throw new AreaException(AreaErrorCode.ERR_AREA_042, buildPendingSchedulesErrorMessage(pendingSchedules));
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

        try {
            Area savedArea = areaRepository.saveAndFlush(area);
            User actor = actorEmail != null ? userRepository.findByEmail(actorEmail).orElse(null) : null;
            auditService.record(
                    AuditTargetType.AREA,
                    AuditAction.UPDATE,
                    savedArea.getId().toString(),
                    savedArea,
                    null,
                    beforeSnapshot,
                    AreaSnapshot.from(savedArea),
                    null,
                    actor
            );
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
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

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
    public void deleteGeometry(UUID id, String actorEmail) {
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        if (area.getGeometry() == null) {
            return;
        }

        resolveActorId(actorEmail);
        AreaGeometrySnapshot beforeSnapshot = AreaGeometrySnapshot.from(area.getGeometry());

        area.setGeometry(null);
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

    private String buildPendingSchedulesErrorMessage(List<com.fa26se040.icss.entity.AreaEventSchedule> schedules) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        java.time.format.DateTimeFormatter dtfDateHour = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zone);
        java.time.format.DateTimeFormatter dtfTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone);

        int count = schedules.size();
        int displayLimit = Math.min(count, 5);
        StringBuilder sb = new StringBuilder();
        sb.append("Khu vực còn ").append(count).append(" lịch sự kiện chưa diễn ra: ");

        for (int i = 0; i < displayLimit; i++) {
            com.fa26se040.icss.entity.AreaEventSchedule s = schedules.get(i);
            String creatorName = s.getCreatedBy() != null ? s.getCreatedBy().getFullName() : "Không xác định";
            sb.append(dtfDateHour.format(s.getStartAt()))
              .append("–")
              .append(dtfTime.format(s.getEndAt()))
              .append(" (")
              .append(creatorName)
              .append(")");
            if (i < displayLimit - 1) {
                sb.append(", ");
            }
        }

        if (count > 5) {
            sb.append(", và ").append(count - 5).append(" lịch khác");
        }

        sb.append(". Liên hệ quản lý cơ sở vật chất để huỷ lịch trước.");
        return sb.toString();
    }

    @Transactional
    public void deactivate(UUID id, String actorEmail) {
        Area area = areaRepository.findByIdWithLock(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        List<com.fa26se040.icss.entity.AreaEventSchedule> pendingSchedules = (eventScheduleRepository != null && id != null)
                ? eventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(id, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED)
                : Collections.emptyList();
        if (!pendingSchedules.isEmpty()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_042, buildPendingSchedulesErrorMessage(pendingSchedules));
        }

        AreaDependencyResponse dep = dependencyChecker.check(id);
        if (!dep.canDeactivate()) {
            AreaDependencyResponse.Blocker firstBlocker = dep.blockers().get(0);
            throw new AreaException(firstBlocker.errorCode(), firstBlocker.count());
        }

        resolveActorId(actorEmail);
        AreaSnapshot beforeSnapshot = AreaSnapshot.from(area);

        area.setIsActive(false);
        area.setDeletedAt(OffsetDateTime.now());

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
        Area area = areaRepository.findByIdWithLock(id)
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

    private void sendGuardEventModeChangedNotification(Area area, com.fa26se040.icss.enums.AuditAction action, User actor, OffsetDateTime openUntil) {
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
    }

    @Transactional
    public AreaResponse updateEventMode(UUID id, AreaEventModeUpdateRequest req, String actorEmail) {
        log.info("Updating event mode for area {}: enabled={}, openUntil={}, reasonCode={}",
                id, req != null ? req.enabled() : null, req != null ? req.openUntil() : null, req != null ? req.reasonCode() : null);

        // 1. Khoá area (B2). Không tồn tại -> lỗi hiện có (ERR_AREA_002)
        Area area = areaRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        // 2. now = OffsetDateTime.now() lấy SAU khi đã có khoá (R4)
        OffsetDateTime now = OffsetDateTime.now();

        // 3. Dọn trong cùng transaction, trên CHÍNH entity area đã khoá (không findById lại):
        cleanupExpiredSessions(area, now);

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
        com.fa26se040.icss.enums.AuditAction action;
        if (!activeNow && targetEnabled) {
            expectedActionType = "EVENT_ENABLE";
            action = com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE;
        } else if (activeNow && targetEnabled) {
            expectedActionType = "EVENT_EXTEND";
            action = com.fa26se040.icss.enums.AuditAction.EXTEND_EVENT_MODE;
        } else if (activeNow && !targetEnabled) {
            expectedActionType = "EVENT_DISABLE";
            action = com.fa26se040.icss.enums.AuditAction.DISABLE_EVENT_MODE;
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

            // Đóng phiên hiện tại tại now TRƯỚC khi tính ngân sách
            if (expectedActionType.equals("EVENT_EXTEND")) {
                com.fa26se040.icss.entity.AreaEventSession currentSession = eventSessionRepository.findByAreaIdAndActualEndIsNull(id).orElse(null);
                if (currentSession != null) {
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
                eventActive,
                timeline.startedAt(),
                timeline.startedByName(),
                timeline.lastAdjustedAt(),
                timeline.lastAdjustedByName(),
                upcomingScheduleCount
        );
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
                eventActive,
                timeline.startedAt(),
                timeline.startedByName(),
                timeline.lastAdjustedAt(),
                timeline.lastAdjustedByName(),
                upcomingScheduleCount
        );
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

    private void cleanupExpiredSessions(Area area, OffsetDateTime now) {
        List<com.fa26se040.icss.entity.AreaEventSession> expiredSessions =
                eventSessionRepository.findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(area.getId(), now);
        for (com.fa26se040.icss.entity.AreaEventSession exp : expiredSessions) {
            exp.setActualEnd(exp.getPlannedEnd());
            exp.setEndedBy(null);
            eventSessionRepository.save(exp);

            auditService.record(
                    com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_MODE,
                    com.fa26se040.icss.enums.AuditAction.EXPIRE_EVENT_MODE,
                    area.getId().toString(),
                    area,
                    null,
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(true, exp.getPlannedEnd()),
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(false, null, null, null, null, exp.getId(), exp.getPlannedEnd(), null),
                    "Chế độ sự kiện tự động hết hạn",
                    com.fa26se040.icss.dto.audit.AuditActor.system("EVENT_MODE_EXPIRY")
            );
        }
        if (Boolean.TRUE.equals(area.getOpenToMembers()) && !area.isEventActive(now)) {
            area.setOpenToMembers(false);
            area.setOpenUntil(null);
        }
    }

    private void sendGuardScheduleNotification(Area area, com.fa26se040.icss.enums.AuditAction action, com.fa26se040.icss.entity.AreaEventSchedule schedule) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
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
                    }
                }
        );
    }

    private void sendFmScheduleFailedNotification(Area area, com.fa26se040.icss.entity.AreaEventSchedule schedule, String failReason) {
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
    }

    private void sendFmScheduleStartingNotification(com.fa26se040.icss.entity.AreaEventSchedule schedule) {
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
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository.findByCode(normReasonCode).orElse(null);
        if (reasonItem == null || !Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        if (!"EVENT_ENABLE".equalsIgnoreCase(reasonItem.getActionType())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_026);
        }
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
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository.findByCode(normReasonCode).orElse(null);
        if (reasonItem == null || !Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        if (!"EVENT_EXTEND".equalsIgnoreCase(reasonItem.getActionType())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_026);
        }
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
        schedule.setReasonCode(normReasonCode);
        schedule.setReasonLabel(reasonLabel);
        schedule.setNote(trimmedNote);
        schedule.setUpdatedBy(actor);
        schedule.setUpdatedAt(now);
        com.fa26se040.icss.entity.AreaEventSchedule saved = eventScheduleRepository.save(schedule);

        com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventScheduleAuditSnapshot newSnapshot =
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
                com.fa26se040.icss.enums.AuditAction.UPDATE,
                saved.getId().toString(),
                area,
                null,
                oldSnapshot,
                newSnapshot,
                trimmedNote,
                actor
        );

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
        com.fa26se040.icss.entity.ReasonCatalog reasonItem = reasonCatalogRepository.findByCode(normReasonCode).orElse(null);
        if (reasonItem == null || !Boolean.TRUE.equals(reasonItem.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_025);
        }
        if (!"EVENT_DISABLE".equalsIgnoreCase(reasonItem.getActionType())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_026);
        }
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
        com.fa26se040.icss.context.AuditContext.runAsSystem("EVENT_MODE_EXPIRY", () -> {
            Area area = areaRepository.findByIdWithLock(areaId).orElse(null);
            if (area == null) {
                return;
            }
            List<com.fa26se040.icss.entity.AreaEventSession> expiredSessions =
                    eventSessionRepository.findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(areaId, now);
            for (com.fa26se040.icss.entity.AreaEventSession exp : expiredSessions) {
                exp.setActualEnd(exp.getPlannedEnd());
                exp.setEndedBy(null);
                eventSessionRepository.save(exp);

                auditService.record(
                        com.fa26se040.icss.enums.AuditTargetType.AREA_EVENT_MODE,
                        com.fa26se040.icss.enums.AuditAction.EXPIRE_EVENT_MODE,
                        area.getId().toString(),
                        area,
                        null,
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(true, exp.getPlannedEnd()),
                        new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot(false, null, null, null, null, exp.getId(), exp.getPlannedEnd(), null),
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
            } else {
                com.fa26se040.icss.entity.AreaEventSession session = com.fa26se040.icss.entity.AreaEventSession.builder()
                        .area(area)
                        .startedAt(schedule.getStartAt())
                        .plannedEnd(schedule.getEndAt())
                        .actualEnd(null)
                        .startedBy(schedule.getCreatedBy())
                        .createdAt(now)
                        .build();
                eventSessionRepository.save(session);

                area.setOpenToMembers(true);
                area.setOpenUntil(schedule.getEndAt());
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

                sendGuardEventModeChangedNotification(area, com.fa26se040.icss.enums.AuditAction.ENABLE_EVENT_MODE, schedule.getCreatedBy(), schedule.getEndAt());
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
