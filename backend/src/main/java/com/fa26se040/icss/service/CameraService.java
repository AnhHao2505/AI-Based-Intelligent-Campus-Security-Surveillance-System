package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.camera.*;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.util.AesEncryptionUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.fa26se040.icss.config.AiServiceRestTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CameraService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    private final MediaMtxService mediaMtxService;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final RoiGeometryValidator roiGeometryValidator;
    private final MinioStorageService minioStorageService;
    private final SystemConfigService systemConfigService;
    private final AreaRepository areaRepository;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${camera.rtsp.timeout-ms:10000}")
    private int defaultTimeoutMs;

    private final AiServiceRestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EdgeStatusService edgeStatusService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FloorRepository floorRepository;

    // === Camera CRUD ===

    public CameraDetailResponse createCamera(CreateCameraRequest request) {
        log.info("Creating camera with name: {}", request.getName());

        if (request.getAreaId() == null) {
            throw new CameraException(CameraErrorCode.ERR_CAM_004);
        }

        String cameraCode = request.getCameraCode();
        if (cameraCode != null && !cameraCode.trim().isEmpty()) {
            cameraCode = cameraCode.trim();
            if (cameraRepository.existsByCameraCode(cameraCode)) {
                throw new CameraException(CameraErrorCode.ERR_CAM_003);
            }
        } else {
            cameraCode = generateCameraCode();
            log.info("Auto-generated camera code: {}", cameraCode);
        }

        Area area = areaRepository.findByIdAndDeletedAtIsNull(request.getAreaId())
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        if (!Boolean.TRUE.equals(area.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        Camera camera = Camera.builder()
                .cameraCode(cameraCode)
                .name(request.getName().trim())
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(area)
                .build();

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    @Transactional
    public Page<CameraListResponse> listCameras(String search, CameraStatus status,
                                                OperationalStatus opStatus, Boolean forceSync, Pageable pageable) {
        log.info("Listing cameras with search: {}, status: {}, operationalStatus: {}, forceSync: {}",
                search, status, opStatus, forceSync);
        if (Boolean.TRUE.equals(forceSync)) {
            syncLiveOperationalStatuses();
        }
        Pageable effectivePageable = pageable;
        if (pageable == null || pageable.getSort().isUnsorted()) {
            effectivePageable = org.springframework.data.domain.PageRequest.of(
                    pageable != null ? pageable.getPageNumber() : 0,
                    pageable != null ? pageable.getPageSize() : 10,
                    org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "cameraCode")
            );
        }
        String searchParam = "%" + (search != null ? search.trim().toLowerCase() : "") + "%";
        Page<Camera> cameras = cameraRepository.findFiltered(searchParam, status, opStatus, effectivePageable);
        return cameras.map(this::mapToListResponse);
    }

    @Transactional
    public Page<CameraListResponse> listCameras(String search, CameraStatus status,
                                                OperationalStatus opStatus, Pageable pageable) {
        return listCameras(search, status, opStatus, false, pageable);
    }

    @Transactional(readOnly = true)
    public List<CameraSimpleResponse> getAllActiveSimple() {
        log.info("Fetching simple list of active cameras");
        return cameraRepository.findByStatusAndDeletedAtIsNullOrderByCameraCodeAsc(CameraStatus.ACTIVE).stream()
                .map(c -> CameraSimpleResponse.builder()
                        .id(c.getId())
                        .cameraCode(c.getCameraCode())
                        .name(c.getName())
                        .status(c.getStatus())
                        .operationalStatus(c.getOperationalStatus())
                        .mapLongitude(c.getMapLongitude())
                        .mapLatitude(c.getMapLatitude())
                        .mapBuilding(c.getMapBuilding())
                        .mapFloorOrder(c.getMapFloorOrder())
                        .build())
                .collect(Collectors.toList());
    }

    private Camera findActiveCameraById(UUID id) {
        return cameraRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));
    }

    @Transactional(readOnly = true)
    public CameraDetailResponse getCameraDetail(UUID id) {
        log.info("Fetching camera detail for id: {}", id);
        Camera camera = findActiveCameraById(id);
        return mapToDetailResponse(camera);
    }

    private static final long STREAM_STARTUP_GRACE_SECONDS = 25L;
    private static final long CLOUD_EDGE_STARTUP_GRACE_SECONDS = 40L;

    /**
     * Tự động kiểm tra trạng thái luồng video từ MediaMTX (hoặc Edge Heartbeat ở chế độ Cloud-Edge)
     * và cập nhật operational_status cho các camera đang ACTIVE.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    @Transactional
    public void syncLiveOperationalStatuses() {
        try {
            boolean edgeOnline = edgeMqttControlService != null
                    && edgeMqttControlService.isConnected()
                    && edgeStatusService != null
                    && edgeStatusService.hasActiveOnlineEdge();
            if (edgeOnline) {
                syncOperationalStatusesFromEdgeHeartbeat();
                return;
            }

            java.util.Map<String, Boolean> liveStatuses = mediaMtxService.getLivePathStatuses();
            if (liveStatuses == null) {
                return;
            }

            List<Camera> activeCameras = cameraRepository
                    .findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE);

            for (Camera cam : activeCameras) {
                String pathName = mediaMtxService.formatPathName(cam.getCameraCode());
                boolean isSubReady = Boolean.TRUE.equals(liveStatuses.get(pathName));
                boolean isMainReady = Boolean.TRUE.equals(liveStatuses.get(pathName + "_main"));
                boolean isReady = isSubReady || isMainReady;
                boolean hasPathInMediaMtx = liveStatuses.containsKey(pathName) || liveStatuses.containsKey(pathName + "_main");
                OperationalStatus currentStatus = cam.getOperationalStatus();

                if (!hasPathInMediaMtx && currentStatus == OperationalStatus.ONLINE) {
                    if (reregisterMissingMediaMtxPath(cam)) {
                        cam.setUpdatedAt(OffsetDateTime.now());
                        cameraRepository.save(cam);
                        continue;
                    }
                }

                OperationalStatus newStatus;
                if (isReady) {
                    newStatus = OperationalStatus.ONLINE;
                } else if (hasPathInMediaMtx && currentStatus == OperationalStatus.ONLINE && isWithinStreamStartupGrace(cam)) {
                    newStatus = OperationalStatus.ONLINE;
                } else {
                    newStatus = OperationalStatus.OFFLINE;
                }

                if (currentStatus != newStatus) {
                    cam.setOperationalStatus(newStatus);
                    cameraRepository.save(cam);
                    log.info("Cập nhật trạng thái kết nối camera [{}]: {} -> {}", cam.getCameraCode(), currentStatus, newStatus);

                    if (newStatus == OperationalStatus.ONLINE) {
                        startAiStreamWorkerSafely(cam.getCameraCode());
                    } else if (newStatus == OperationalStatus.OFFLINE) {
                        stopAiStreamWorkerSafely(cam.getCameraCode());
                    }
                } else if (newStatus == OperationalStatus.ONLINE) {
                    ensureAiWorkerRunningIfNeeded(cam.getCameraCode());
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi đồng bộ trạng thái kết nối từ MediaMTX: {}", e.getMessage());
        }
    }

    private boolean reregisterMissingMediaMtxPath(Camera cam) {
        CameraStreamConfiguration cfg = cameraStreamConfigurationRepository.findByCameraId(cam.getId()).orElse(null);
        if (cfg == null) {
            return false;
        }
        String mainRtspUrl = buildRtspUrl(cfg);
        if (mainRtspUrl == null || mainRtspUrl.isBlank()) {
            return false;
        }
        String subRtspUrl = buildSubRtspUrl(cfg);
        boolean hasSub = (subRtspUrl != null && !subRtspUrl.isBlank());
        if (hasSub) {
            mediaMtxService.syncCameraPath(cam.getCameraCode(), subRtspUrl);
            mediaMtxService.syncCameraPath(cam.getCameraCode() + "_main", mainRtspUrl);
        } else {
            mediaMtxService.syncCameraPath(cam.getCameraCode(), mainRtspUrl);
        }
        log.info("🔄 [MediaMTX Re-sync] Đã đăng ký lại path bị mất cho camera ONLINE [{}]", cam.getCameraCode());
        return true;
    }

    private void syncOperationalStatusesFromEdgeHeartbeat() {
        Map<String, Boolean> edgeCamStatuses = edgeStatusService.getOnlineEdgeCameraStatuses();
        if (edgeCamStatuses == null) {
            return;
        }
        List<Camera> activeCameras = cameraRepository.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE);
        for (Camera cam : activeCameras) {
            String code = cam.getCameraCode() != null ? cam.getCameraCode().trim().toUpperCase(Locale.ROOT) : "";
            Boolean edgeConnected = edgeCamStatuses.get(code);
            OperationalStatus currentStatus = cam.getOperationalStatus();

            OperationalStatus newStatus;
            if (Boolean.TRUE.equals(edgeConnected)) {
                newStatus = OperationalStatus.ONLINE;
            } else if (edgeConnected == null && currentStatus == OperationalStatus.ONLINE
                    && isWithinGraceSeconds(cam, CLOUD_EDGE_STARTUP_GRACE_SECONDS)) {
                newStatus = OperationalStatus.ONLINE;
            } else {
                newStatus = OperationalStatus.OFFLINE;
            }

            if (currentStatus != newStatus) {
                cam.setOperationalStatus(newStatus);
                cameraRepository.save(cam);
                log.info("🔄 [Cloud-Edge Sync] Cập nhật trạng thái camera [{}]: {} -> {}",
                        cam.getCameraCode(), currentStatus, newStatus);
            }
        }
    }

    private boolean isWithinStreamStartupGrace(Camera camera) {
        return isWithinGraceSeconds(camera, STREAM_STARTUP_GRACE_SECONDS);
    }

    private boolean isWithinGraceSeconds(Camera camera, long graceSeconds) {
        if (camera == null || camera.getUpdatedAt() == null) {
            return false;
        }
        return !camera.getUpdatedAt().isBefore(OffsetDateTime.now().minusSeconds(graceSeconds));
    }

    public CameraDetailResponse updateCamera(UUID id, UpdateCameraRequest request) {
        log.info("Updating camera basic details for id: {}", id);
        Camera camera = findActiveCameraById(id);
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }

        camera.setName(request.getName());
        if (request.getAreaId() != null) {
            Area area = areaRepository.findByIdAndDeletedAtIsNull(request.getAreaId())
                    .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
            if (!Boolean.TRUE.equals(area.getIsActive())) {
                throw new AreaException(AreaErrorCode.ERR_AREA_017);
            }
            camera.setArea(area);
        }

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    /**
     * Cập nhật tọa độ icon camera trên bản đồ 2D/3D (F-05: kiểm tra tọa độ + khớp tòa/tầng với CSDL & Area).
     */
    public CameraDetailResponse updateMapLocation(UUID id, CameraMapLocationRequest request) {
        log.info("Updating map location for camera id: {}", id);
        Camera camera = findActiveCameraById(id);
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_MAP_002);
        }

        Double lng = request.getLongitude();
        Double lat = request.getLatitude();
        if (lng == null || lat == null || !Double.isFinite(lng) || !Double.isFinite(lat)
                || lng < -180.0 || lng > 180.0 || lat < -90.0 || lat > 90.0) {
            throw new CameraException(CameraErrorCode.ERR_MAP_003);
        }

        String building = request.getBuilding() != null && !request.getBuilding().isBlank()
                ? request.getBuilding().trim()
                : null;
        Integer floorOrder = request.getFloorOrder();

        Area assignedArea = camera.getArea();
        if (floorOrder != null && building == null) {
            if (assignedArea != null && assignedArea.getBuilding() != null && !assignedArea.getBuilding().isBlank()) {
                building = assignedArea.getBuilding().trim();
            } else {
                throw new CameraException(CameraErrorCode.ERR_MAP_004);
            }
        }

        if (building != null) {
            if (assignedArea != null && assignedArea.getBuilding() != null && !assignedArea.getBuilding().isBlank()
                    && !matchesBuildingName(building, assignedArea.getBuilding())) {
                throw new CameraException(CameraErrorCode.ERR_MAP_004);
            }

            if (floorRepository != null) {
                List<Floor> activeFloors = floorRepository.findAllActiveFloors();
                List<Floor> buildingFloors = new ArrayList<>();
                for (Floor f : activeFloors) {
                    if (f.getBuilding() != null && matchesBuildingName(building, f.getBuilding().getName())) {
                        buildingFloors.add(f);
                    }
                }
                if (buildingFloors.isEmpty()) {
                    throw new CameraException(CameraErrorCode.ERR_MAP_004);
                }
                building = buildingFloors.get(0).getBuilding().getName();

                if (floorOrder != null) {
                    Floor matchedFloor = buildingFloors.stream()
                            .filter(f -> Objects.equals(f.getFloorOrder(), floorOrder))
                            .findFirst()
                            .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_MAP_004));

                    if (assignedArea != null) {
                        if (assignedArea.getFloorEntity() != null
                                && !Objects.equals(assignedArea.getFloorEntity().getFloorOrder(), floorOrder)) {
                            throw new CameraException(CameraErrorCode.ERR_MAP_004);
                        }
                        if (assignedArea.getFloorEntity() == null
                                && assignedArea.getFloor() != null
                                && !assignedArea.getFloor().isBlank()
                                && !matchedFloor.getName().trim().equalsIgnoreCase(assignedArea.getFloor().trim())) {
                            throw new CameraException(CameraErrorCode.ERR_MAP_004);
                        }
                    }
                }
            } else if (floorOrder != null && assignedArea != null && assignedArea.getFloorEntity() != null
                    && !Objects.equals(assignedArea.getFloorEntity().getFloorOrder(), floorOrder)) {
                throw new CameraException(CameraErrorCode.ERR_MAP_004);
            }
        }

        camera.setMapLongitude(lng);
        camera.setMapLatitude(lat);
        camera.setMapBuilding(building);
        camera.setMapFloorOrder(floorOrder);

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    private static boolean matchesBuildingName(String b1, String b2) {
        String n1 = normalizeBuildingName(b1);
        String n2 = normalizeBuildingName(b2);
        return !n1.isEmpty() && n1.equals(n2);
    }

    private static String normalizeBuildingName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        if (normalized.startsWith("toa ")) {
            normalized = normalized.substring(4).trim();
        }
        return normalized;
    }

    /**
     * Gỡ tọa độ icon camera khỏi bản đồ (không xóa camera).
     */
    public CameraDetailResponse clearMapLocation(UUID id) {
        log.info("Clearing map location for camera id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        camera.setMapLongitude(null);
        camera.setMapLatitude(null);
        camera.setMapBuilding(null);
        camera.setMapFloorOrder(null);

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    public CameraDetailResponse decommissionCamera(UUID id) {
        log.info("Decommissioning camera for id: {}", id);
        Camera camera = findActiveCameraById(id);

        camera.setStatus(CameraStatus.DECOMMISSIONED);
        camera.setOperationalStatus(OperationalStatus.OFFLINE);

        Camera saved = cameraRepository.save(camera);
        mediaMtxService.deleteCameraPath(camera.getCameraCode());
        stopAiStreamWorkerSafely(camera.getCameraCode());
        return mapToDetailResponse(saved);
    }

    public void deleteCamera(UUID id) {
        log.info("Soft deleting camera for id: {}", id);
        Camera camera = findActiveCameraById(id);

        camera.setStatus(CameraStatus.DECOMMISSIONED);
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setDeletedAt(OffsetDateTime.now());

        cameraRepository.save(camera);
        mediaMtxService.deleteCameraPath(camera.getCameraCode());
        stopAiStreamWorkerSafely(camera.getCameraCode());
    }

    public CameraDetailResponse reactivateCamera(UUID id) {
        log.info("Reactivating camera for id: {}", id);
        // Camera đã xoá mềm coi như không tồn tại; chỉ camera DECOMMISSIONED chưa xoá mới kích hoạt lại được
        Camera camera = cameraRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        Area area = camera.getArea();
        if (area == null || area.getDeletedAt() != null || !Boolean.TRUE.equals(area.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        camera.setStatus(CameraStatus.ACTIVE);
        camera.setDeletedAt(null);

        Camera saved = cameraRepository.save(camera);
        cameraStreamConfigurationRepository.findByCameraId(id).ifPresent(cfg -> {
            if (hasActiveRtspConflict(id, cfg.getHost(), cfg.getPort(), cfg.getMainStreamPath())) {
                log.warn("Gỡ cấu hình RTSP cũ của camera [{}] khi kích hoạt lại vì endpoint [{}:{}{}] đã được camera khác sử dụng",
                        camera.getCameraCode(), cfg.getHost(), cfg.getPort(), cfg.getMainStreamPath());
                cameraStreamConfigurationRepository.delete(cfg);
                saved.setStreamConfiguration(null);
                return;
            }
            String mainRtspUrl = buildRtspUrl(cfg);
            String subRtspUrl = buildSubRtspUrl(cfg);
            boolean hasSub = (subRtspUrl != null && !subRtspUrl.isBlank());
            if (mainRtspUrl != null) {
                if (hasSub) {
                    mediaMtxService.syncCameraPath(camera.getCameraCode(), subRtspUrl);
                    mediaMtxService.syncCameraPath(camera.getCameraCode() + "_main", mainRtspUrl);
                } else {
                    mediaMtxService.syncCameraPath(camera.getCameraCode(), mainRtspUrl);
                }
            }
        });
        return mapToDetailResponse(saved);
    }

    // === Configuration Upsert ===

    public CameraStreamConfigResponse upsertStreamConfig(UUID cameraId, CameraStreamConfigRequest req) {
        log.info("Upserting stream configuration for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }
        if (camera.getOperationalStatus() == OperationalStatus.ONLINE) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_004);
        }
        if (hasActiveRtspConflict(cameraId, req.getHost(), req.getPort(), req.getMainStreamPath())) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_005);
        }

        CameraStreamConfiguration config = cameraStreamConfigurationRepository.findByCameraId(cameraId)
                .orElse(CameraStreamConfiguration.builder().camera(camera).build());

        String rawPassword = req.getEffectivePassword();
        if (rawPassword != null && !rawPassword.isBlank()) {
            config.setCredentialRef(aesEncryptionUtil.encrypt(rawPassword));
        }

        config.setHost(req.getHost().trim());
        config.setPort(req.getPort());
        config.setUsername(req.getUsername() != null ? req.getUsername().trim() : null);
        config.setMainStreamPath(req.getMainStreamPath().trim());
        config.setSubStreamPath(req.getSubStreamPath() != null ? req.getSubStreamPath().trim() : null);

        CameraStreamConfiguration saved = cameraStreamConfigurationRepository.save(config);

        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        CameraStreamConfigResponse streamResponse = mapToStreamResponse(saved);
        streamResponse.setOperationalStatus(camera.getOperationalStatus() != null ? camera.getOperationalStatus() : OperationalStatus.OFFLINE);
        return streamResponse;
    }

    // === ROI Configuration ===

    public CameraDetailResponse updateRoiGeometry(UUID cameraId, RoiUpdateRequest request) {
        log.info("Updating ROI geometry for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }

        RoiGeometry roi = request.getRoiGeometry();
        if (roi == null) {
            roi = RoiGeometry.builder().polygons(new ArrayList<>()).build();
        }
        roiGeometryValidator.validate(roi);

        camera.setRoiGeometry(roi);
        camera.setUpdatedAt(OffsetDateTime.now());
        Camera saved = cameraRepository.save(camera);

        syncRoiToAiService(camera.getCameraCode(), saved.getRoiGeometry());

        return mapToDetailResponse(saved);
    }

    private void syncRoiToAiService(String cameraCode, RoiGeometry roi) {
        if (roi == null) {
            return;
        }
        boolean hasMqtt = edgeMqttControlService != null && edgeMqttControlService.isConnected();
        boolean hasHttp = aiServiceUrl != null && !aiServiceUrl.isBlank();
        if (!hasMqtt && !hasHttp) {
            return;
        }
        try {
            String endpoint = aiServiceUrl + "/api/v1/cameras/configure";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            List<Map<String, Object>> polygonsList = new ArrayList<>();
            if (roi.getPolygons() != null) {
                for (RoiGeometry.RoiPolygon p : roi.getPolygons()) {
                    Map<String, Object> polyMap = new HashMap<>();
                    polyMap.put("label", p.getLabel() != null ? p.getLabel() : "");
                    List<Map<String, Object>> verticesList = new ArrayList<>();
                    if (p.getVertices() != null) {
                        for (RoiGeometry.RoiPolygon.Vertex v : p.getVertices()) {
                            if (v.getX() != null && v.getY() != null) {
                                verticesList.add(Map.of(
                                        "x", v.getX().doubleValue(),
                                        "y", v.getY().doubleValue()
                                ));
                            }
                        }
                    }
                    polyMap.put("vertices", verticesList);
                    polygonsList.add(polyMap);
                }
            }

            Map<String, Object> body = new HashMap<>();
            body.put("camera_code", cameraCode);
            body.put("after_hour_start", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START));
            body.put("after_hour_end", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END));
            body.put("reference_width", roi.getReferenceSnapshotWidth() != null ? roi.getReferenceSnapshotWidth() : 1920);
            body.put("reference_height", roi.getReferenceSnapshotHeight() != null ? roi.getReferenceSnapshotHeight() : 1080);
            body.put("polygons", polygonsList);

            if (hasMqtt) {
                edgeMqttControlService.publishRoiConfig(cameraCode, body);
                if (!hasHttp) {
                    return;
                }
            }
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(endpoint, httpEntity, Map.class);
            log.info("Successfully synced ROI geometry to AI Service for camera [{}]", cameraCode);
        } catch (Exception e) {
            log.warn("Could not sync ROI geometry to AI Service for camera [{}]: {}", cameraCode, e.getMessage());
        }
    }

    private void ensureAiWorkerRunningIfNeeded(String cameraCode) {
        if (cameraCode == null || cameraCode.isBlank() || aiServiceUrl == null || aiServiceUrl.isBlank()) {
            return;
        }
        try {
            String statusUrl = aiServiceUrl + "/api/v1/stream/status?camera_code=" + cameraCode;
            @SuppressWarnings("unchecked")
            Map<String, Object> statusResp = restTemplate.getForObject(statusUrl, Map.class);
            if (statusResp != null && !Boolean.TRUE.equals(statusResp.get("is_running"))) {
                log.info("🔄 [AI Recovery] Camera [{}] đang ONLINE nhưng AI Worker chưa chạy -> thử khởi động lại", cameraCode);
                startAiStreamWorkerSafely(cameraCode);
            }
        } catch (Exception ex) {
            log.debug("Không thể kiểm tra trạng thái AI Stream Worker cho camera [{}]: {}", cameraCode, ex.getMessage());
        }
    }

    private void startAiStreamWorkerSafely(String cameraCode) {
        if (cameraCode == null || cameraCode.isBlank()) {
            return;
        }
        try {
            Camera camera = cameraRepository.findByCameraCodeAndDeletedAtIsNull(cameraCode).orElse(null);
            boolean hasSub = false;
            String mainRtspUrl = null;
            String subRtspUrl = null;
            if (camera != null) {
                CameraStreamConfiguration cfg = cameraStreamConfigurationRepository.findByCameraId(camera.getId()).orElse(null);
                if (cfg != null) {
                    mainRtspUrl = buildRtspUrl(cfg);
                    subRtspUrl = buildSubRtspUrl(cfg);
                    hasSub = (subRtspUrl != null && !subRtspUrl.isBlank());
                }
            }

            if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                if (hasSub) {
                    edgeMqttControlService.publishCameraStreamConfig(
                            cameraCode,
                            subRtspUrl,
                            mainRtspUrl,
                            true,
                            camera != null ? camera.getRoiGeometry() : null
                    );
                } else {
                    edgeMqttControlService.publishCameraStreamConfig(
                            cameraCode,
                            mainRtspUrl,
                            true,
                            camera != null ? camera.getRoiGeometry() : null
                    );
                }
            }

            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
            String streamStartUrl = aiServiceUrl + "/api/v1/stream/start";
            String workerRtspUrl = hasSub
                    ? mediaMtxService.getGatewayRtspUrl(cameraCode + "_main")
                    : mediaMtxService.getGatewayRtspUrl(cameraCode);
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("camera_code", cameraCode);
            reqBody.put("rtsp_url", workerRtspUrl);
            if (camera != null && camera.getRoiGeometry() != null) {
                reqBody.put("roi_geometry", camera.getRoiGeometry());
            }
            restTemplate.postForObject(streamStartUrl, reqBody, Map.class);
            log.info("✅ Đã tự động kích hoạt AI Stream Worker & Continuous Recording cho camera [{}] qua MediaMTX Gateway ({})",
                    cameraCode, MediaMtxService.maskRtspCredentials(workerRtspUrl));
        } catch (Exception ex) {
            log.warn("⚠️ Không thể tự động kích hoạt AI Stream Worker cho camera [{}]: {}", cameraCode, ex.getMessage());
        }
    }

    private void stopAiStreamWorkerSafely(String cameraCode) {
        if (cameraCode != null && !cameraCode.isBlank() && edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishCameraStreamConfig(cameraCode, "", false, null);
            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
        }
        if (cameraCode == null || cameraCode.isBlank() || aiServiceUrl == null || aiServiceUrl.isBlank()) {
            return;
        }
        try {
            String streamStopUrl = aiServiceUrl + "/api/v1/stream/stop?camera_code=" + cameraCode;
            restTemplate.postForObject(streamStopUrl, null, Map.class);
            log.info("✅ Đã dừng AI Stream Worker cho camera [{}]", cameraCode);
        } catch (Exception ex) {
            log.debug("Không thể dừng AI stream worker cho camera [{}] (có thể worker chưa chạy): {}", cameraCode, ex.getMessage());
        }
    }

    // === Helpers & Mapping ===

    private String generateCameraCode() {
        try {
            Long nextSeq = cameraRepository.getNextCameraCodeSequence();
            if (nextSeq != null) {
                return String.format("CAM-%03d", nextSeq);
            }
        } catch (Exception e) {
            log.warn("Could not fetch next sequence from DB, falling back: {}", e.getMessage());
        }
        Optional<Camera> latestCamera = cameraRepository.findTopByCameraCodeStartingWithOrderByCameraCodeDesc("CAM-");
        if (latestCamera.isEmpty()) {
            return "CAM-001";
        }
        String lastCode = latestCamera.get().getCameraCode();
        try {
            if (lastCode.length() > 4 && lastCode.startsWith("CAM-")) {
                String numericPart = lastCode.substring(4);
                int nextNumber = Integer.parseInt(numericPart) + 1;
                return String.format("CAM-%03d", nextNumber);
            }
        } catch (Exception e) {
            log.warn("Failed to parse numeric part of camera code: {}, generating UUID-based code", lastCode, e);
        }
        return "CAM-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private CameraListResponse mapToListResponse(Camera camera) {
        Area area = camera.getArea();
        boolean hasActiveArea = area != null && area.getDeletedAt() == null;
        return CameraListResponse.builder()
                .id(camera.getId())
                .cameraCode(camera.getCameraCode())
                .name(camera.getName())
                .status(camera.getStatus())
                .operationalStatus(camera.getOperationalStatus())
                .areaId(hasActiveArea ? area.getId() : null)
                .areaName(hasActiveArea ? area.getName() : null)
                .building(hasActiveArea ? area.getBuilding() : null)
                .floor(hasActiveArea ? area.getFloor() : null)
                .mapLongitude(camera.getMapLongitude())
                .mapLatitude(camera.getMapLatitude())
                .mapBuilding(camera.getMapBuilding())
                .mapFloorOrder(camera.getMapFloorOrder())
                .build();
    }

    private CameraDetailResponse mapToDetailResponse(Camera camera) {
        Area a = camera.getArea();
        AreaSimpleResponse assignedArea = (a != null && a.getDeletedAt() == null)
                ? new AreaSimpleResponse(
                        a.getId(),
                        a.getName(),
                        a.getAreaLevel(),
                        a.getBuilding(),
                        a.getFloor()
                )
                : null;

        return CameraDetailResponse.builder()
                .id(camera.getId())
                .cameraCode(camera.getCameraCode())
                .name(camera.getName())
                .status(camera.getStatus())
                .operationalStatus(camera.getOperationalStatus())
                .createdAt(camera.getCreatedAt())
                .updatedAt(camera.getUpdatedAt())
                .streamConfig(camera.getStreamConfiguration() != null ? mapToStreamResponse(camera.getStreamConfiguration()) : null)
                .roiGeometry(camera.getRoiGeometry())
                .assignedArea(assignedArea)
                .assignedAreas(assignedArea != null ? List.of(assignedArea) : List.of())
                .mapLongitude(camera.getMapLongitude())
                .mapLatitude(camera.getMapLatitude())
                .mapBuilding(camera.getMapBuilding())
                .mapFloorOrder(camera.getMapFloorOrder())
                .build();
    }

    private CameraStreamConfigResponse mapToStreamResponse(CameraStreamConfiguration config) {
        String cameraCode = (config.getCamera() != null) ? config.getCamera().getCameraCode() : "";
        String pathName = mediaMtxService.formatPathName(cameraCode);
        String configuredWhep = mediaMtxService.getWhepUrl(cameraCode);
        String whepUrl = (configuredWhep != null) ? configuredWhep : ((!pathName.isEmpty()) ? "http://localhost:8889/" + pathName + "/whep" : null);

        return CameraStreamConfigResponse.builder()
                .id(config.getId())
                .host(config.getHost())
                .port(config.getPort())
                .username(config.getUsername())
                .isPasswordConfigured(config.getCredentialRef() != null && !config.getCredentialRef().isBlank())
                .mainStreamPath(config.getMainStreamPath())
                .subStreamPath(config.getSubStreamPath())
                .whepUrl(whepUrl)
                .whepStreamUrl(whepUrl)
                .updatedAt(config.getCamera() != null ? config.getCamera().getUpdatedAt() : null)
                .operationalStatus(config.getCamera() != null ? config.getCamera().getOperationalStatus() : null)
                .build();
    }

    public String decryptCredential(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        return aesEncryptionUtil.decrypt(credentialRef);
    }

    public String buildSubRtspUrl(CameraStreamConfiguration config) {
        if (config == null || config.getSubStreamPath() == null || config.getSubStreamPath().isBlank()) {
            return null;
        }
        String password = null;
        if (config.getCredentialRef() != null && !config.getCredentialRef().isBlank()) {
            password = aesEncryptionUtil.decrypt(config.getCredentialRef());
        }
        return buildRtspUrlWithCredentials(
                config.getHost(),
                config.getPort(),
                config.getUsername(),
                password,
                config.getSubStreamPath()
        );
    }

    public String buildRtspUrl(CameraStreamConfiguration config) {
        if (config == null) {
            return null;
        }
        String password = null;
        if (config.getCredentialRef() != null && !config.getCredentialRef().isBlank()) {
            password = aesEncryptionUtil.decrypt(config.getCredentialRef());
        }
        return buildRtspUrlWithCredentials(
                config.getHost(),
                config.getPort(),
                config.getUsername(),
                password,
                config.getMainStreamPath()
        );
    }

    public String buildRtspUrlWithCredentials(String host, Integer port, String username, String password, String path) {
        if (path != null && (path.startsWith("rtsp://") || path.startsWith("rtsps://"))) {
            return path;
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.startsWith("rtsp://") || host.startsWith("rtsps://")) {
            return host;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("rtsp://");

        if (username != null && !username.isBlank()) {
            sb.append(username.trim());
            if (password != null && !password.isBlank()) {
                sb.append(":").append(password);
            }
            sb.append("@");
        }

        sb.append(host.trim());
        if (port != null) {
            sb.append(":").append(port);
        }

        if (path != null && !path.isBlank()) {
            if (!path.startsWith("/")) {
                sb.append("/");
            }
            sb.append(path.trim());
        }
        return sb.toString();
    }

    private boolean hasActiveRtspConflict(UUID cameraId, String host, Integer port, String mainStreamPath) {
        if (host == null || host.isBlank() || port == null || mainStreamPath == null || mainStreamPath.isBlank()) {
            return false;
        }
        return cameraStreamConfigurationRepository.existsActiveRtspConflict(
                cameraId,
                host.trim(),
                port,
                normalizeMainStreamPathForComparison(mainStreamPath),
                CameraStatus.DECOMMISSIONED
        );
    }

    private String normalizeMainStreamPathForComparison(String path) {
        String trimmed = path.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://") || trimmed.startsWith("/")) {
            return trimmed;
        }
        return "/" + trimmed;
    }

}

