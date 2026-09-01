package com.fa26se040.security.service;

import com.fa26se040.security.dto.area.AreaCreateRequest;
import com.fa26se040.security.dto.area.AreaDependencyResponse;
import com.fa26se040.security.dto.area.AreaGeometry;
import com.fa26se040.security.dto.area.AreaGeometryResponse;
import com.fa26se040.security.dto.area.AreaLevelResponse;
import com.fa26se040.security.dto.area.AreaListItemResponse;
import com.fa26se040.security.dto.area.AreaResponse;
import com.fa26se040.security.dto.area.AreaUpdateRequest;
import com.fa26se040.security.entity.Area;
import com.fa26se040.security.entity.AreaChangeLog;
import com.fa26se040.security.entity.AreaLevel;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.exception.AreaErrorCode;
import com.fa26se040.security.exception.AreaException;
import com.fa26se040.security.exception.UnauthorizedException;
import com.fa26se040.security.repository.AreaChangeLogRepository;
import com.fa26se040.security.repository.AreaLevelRepository;
import com.fa26se040.security.repository.AreaRepository;
import com.fa26se040.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaRepository areaRepository;
    private final AreaLevelRepository areaLevelRepository;
    private final AreaChangeLogRepository areaChangeLogRepository;
    private final UserRepository userRepository;
    private final AreaValidator areaValidator;
    private final AreaDependencyChecker dependencyChecker;
    private final AreaGeometryValidator geometryValidator;

    @Transactional(readOnly = true)
    public Page<AreaListItemResponse> getAreas(String keyword, Short areaLevel, String building, Boolean isActive, Pageable pageable) {
        String cleanKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        String cleanBuilding = (building != null && !building.trim().isEmpty()) ? building.trim() : null;
        Page<Area> page = areaRepository.searchAreas(cleanKeyword, areaLevel, cleanBuilding, isActive, pageable);
        return page.map(this::mapToAreaListItemResponse);
    }

    @Transactional(readOnly = true)
    public AreaResponse getAreaById(UUID id) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        return mapToAreaResponse(area);
    }

    @Transactional(readOnly = true)
    public AreaDependencyResponse getDependencies(UUID id) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        return dependencyChecker.check(id, area.getCode());
    }

    @Transactional(readOnly = true)
    public List<AreaLevelResponse> getAreaLevels() {
        return areaLevelRepository.findByIsActiveTrueOrderByLevelAsc()
                .stream()
                .map(this::mapToAreaLevelResponse)
                .toList();
    }

    @Transactional
    public AreaResponse create(AreaCreateRequest req, String actorEmail) {
        String code = areaValidator.validateAndNormalizeCode(req.code());
        String name = areaValidator.validateAndNormalizeName(req.name());

        if (areaRepository.existsByCodeAndDeletedAtIsNull(code)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_001);
        }

        AreaLevel level = areaLevelRepository.findByLevelAndIsActiveTrue(req.areaLevel())
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_003));

        areaValidator.validateMapCoordinates(req.mapX(), req.mapY());

        UUID actorId = resolveActorId(actorEmail);

        Area area = Area.builder()
                .code(code)
                .name(name)
                .areaLevel(level)
                .building(req.building() != null ? req.building().trim() : null)
                .floor(req.floor() != null ? req.floor().trim() : null)
                .description(req.description())
                .mapX(req.mapX())
                .mapY(req.mapY())
                .isActive(true)
                .createdBy(actorId)
                .updatedBy(actorId)
                .build();

        Area savedArea = areaRepository.save(area);
        logChange(savedArea.getId(), actorId, "CREATE", null, snapshot(savedArea), null);

        return mapToAreaResponse(savedArea);
    }

    @Transactional
    public AreaResponse update(UUID id, AreaUpdateRequest req, String actorEmail) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        // 7b — BR-41: Block changing building or floor when the Area already has geometry
        if (area.getGeometry() != null) {
            String newBuilding = req.building() != null ? req.building().trim() : null;
            String currentBuilding = area.getBuilding() != null ? area.getBuilding().trim() : null;
            boolean buildingChanged = (newBuilding == null && currentBuilding != null)
                    || (newBuilding != null && !newBuilding.equalsIgnoreCase(currentBuilding));

            String newFloor = req.floor() != null ? req.floor().trim() : null;
            String currentFloor = area.getFloor() != null ? area.getFloor().trim() : null;
            boolean floorChanged = (newFloor == null && currentFloor != null)
                    || (newFloor != null && !newFloor.equalsIgnoreCase(currentFloor));

            if (buildingChanged || floorChanged) {
                throw new AreaException(AreaErrorCode.ERR_AREA_014);
            }
        }

        Map<String, Object> oldSnapshot = snapshot(area);

        areaValidator.validateCodeUpdate(req.code(), area.getCode());
        String name = areaValidator.validateAndNormalizeName(req.name());

        AreaLevel newLevel = areaLevelRepository.findByLevelAndIsActiveTrue(req.areaLevel())
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_003));

        areaValidator.validateDowngradeReason(area.getAreaLevel().getLevel(), newLevel.getLevel(), req.reason());
        areaValidator.validateMapCoordinates(req.mapX(), req.mapY());

        UUID actorId = resolveActorId(actorEmail);

        area.setName(name);
        area.setAreaLevel(newLevel);
        area.setBuilding(req.building() != null ? req.building().trim() : null);
        area.setFloor(req.floor() != null ? req.floor().trim() : null);
        area.setDescription(req.description());
        area.setMapX(req.mapX());
        area.setMapY(req.mapY());
        area.setUpdatedBy(actorId);

        Area savedArea = areaRepository.save(area);
        logChange(id, actorId, "UPDATE", oldSnapshot, snapshot(savedArea), req.reason());

        return mapToAreaResponse(savedArea);
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

        // FIX-5: Always normalize type and version at backend
        geometry.setType("polygon");
        geometry.setVersion(1);

        UUID actorId = resolveActorId(actorEmail);
        Map<String, Object> oldSnapshot = snapshot(area);

        area.setGeometry(geometry);
        area.setUpdatedBy(actorId);

        Area savedArea = areaRepository.save(area);
        logChange(id, actorId, "UPDATE_GEOMETRY", oldSnapshot, snapshot(savedArea), null);

        return new AreaGeometryResponse(
                savedArea.getId(),
                savedArea.getCode(),
                savedArea.getName(),
                savedArea.getAreaLevel().getLevel(),
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

        UUID actorId = resolveActorId(actorEmail);
        Map<String, Object> oldSnapshot = snapshot(area);

        area.setGeometry(null);
        area.setUpdatedBy(actorId);

        Area savedArea = areaRepository.save(area);
        logChange(id, actorId, "DELETE_GEOMETRY", oldSnapshot, snapshot(savedArea), null);
    }

    @Transactional(readOnly = true)
    public List<AreaGeometryResponse> getGeometriesByBuildingAndFloor(String building, String floor) {
        List<Area> areas = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(building, floor);
        return areas.stream()
                .map(a -> new AreaGeometryResponse(
                        a.getId(),
                        a.getCode(),
                        a.getName(),
                        a.getAreaLevel().getLevel(),
                        a.getIsActive(),
                        a.getGeometry()
                ))
                .toList();
    }

    @Transactional
    public void deactivate(UUID id, String actorEmail) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));

        AreaDependencyResponse dep = dependencyChecker.check(id, area.getCode());
        if (!dep.canDeactivate()) {
            AreaDependencyResponse.Blocker firstBlocker = dep.blockers().get(0);
            throw new AreaException(firstBlocker.errorCode(), firstBlocker.count());
        }

        Map<String, Object> oldSnapshot = snapshot(area);
        UUID actorId = resolveActorId(actorEmail);

        area.setIsActive(false);
        area.setDeletedAt(OffsetDateTime.now());
        area.setUpdatedBy(actorId);

        Area savedArea = areaRepository.save(area);
        logChange(id, actorId, "DEACTIVATE", oldSnapshot, snapshot(savedArea), null);
    }

    private UUID resolveActorId(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    private Map<String, Object> snapshot(Area area) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", area.getCode());
        map.put("name", area.getName());
        map.put("areaLevel", area.getAreaLevel().getLevel());
        map.put("building", area.getBuilding());
        map.put("floor", area.getFloor());
        map.put("mapX", area.getMapX());
        map.put("mapY", area.getMapY());
        map.put("geometry", area.getGeometry());
        map.put("isActive", area.getIsActive());
        return map;
    }

    private void logChange(UUID areaId, UUID actorId, String action, Map<String, Object> oldVal, Map<String, Object> newVal, String reason) {
        AreaChangeLog changeLog = AreaChangeLog.builder()
                .areaId(areaId)
                .actorId(actorId)
                .action(action)
                .oldValue(oldVal)
                .newValue(newVal)
                .reason(reason)
                .build();
        areaChangeLogRepository.save(changeLog);
    }

    private AreaResponse mapToAreaResponse(Area area) {
        return new AreaResponse(
                area.getId(),
                area.getCode(),
                area.getName(),
                mapToAreaLevelResponse(area.getAreaLevel()),
                area.getBuilding(),
                area.getFloor(),
                area.getDescription(),
                area.getMapX(),
                area.getMapY(),
                area.getIsActive(),
                area.getCreatedBy(),
                area.getCreatedAt(),
                area.getUpdatedBy(),
                area.getUpdatedAt()
        );
    }

    private AreaListItemResponse mapToAreaListItemResponse(Area area) {
        return new AreaListItemResponse(
                area.getId(),
                area.getCode(),
                area.getName(),
                mapToAreaLevelResponse(area.getAreaLevel()),
                area.getBuilding(),
                area.getFloor(),
                area.getIsActive()
        );
    }

    private AreaLevelResponse mapToAreaLevelResponse(AreaLevel level) {
        return new AreaLevelResponse(
                level.getLevel(),
                level.getCode(),
                level.getName(),
                level.getRequiresFaceRecognition(),
                level.getDescription()
        );
    }
}
