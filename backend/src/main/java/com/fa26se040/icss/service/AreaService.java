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

    private AreaResponse mapToAreaResponse(Area area) {
        return mapToAreaResponse(area, false);
    }

    private AreaResponse mapToAreaResponse(Area area, boolean differsFromPreset) {
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
                differsFromPreset
        );
    }

    private AreaListItemResponse mapToAreaListItemResponse(Area area, boolean differsFromPreset) {
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
                differsFromPreset
        );
    }
}
