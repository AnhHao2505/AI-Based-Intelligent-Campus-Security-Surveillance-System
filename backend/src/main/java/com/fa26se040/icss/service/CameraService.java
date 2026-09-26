package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import org.springframework.web.client.RestTemplate;

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
    private final CameraHealthLogRepository cameraHealthLogRepository;
    private final MediaMtxService mediaMtxService;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final RoiGeometryValidator roiGeometryValidator;
    private final MinioStorageService minioStorageService;
    private final SystemConfigService systemConfigService;
    private final AreaRepository areaRepository;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // === Camera CRUD ===

    public CameraDetailResponse createCamera(CreateCameraRequest request) {
        log.info("Creating camera with name: {}", request.getName());

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

        Area area = null;
        if (request.getAreaId() != null) {
            area = areaRepository.findByIdAndDeletedAtIsNull(request.getAreaId())
                    .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
            if (!Boolean.TRUE.equals(area.getIsActive())) {
                throw new AreaException(AreaErrorCode.ERR_AREA_017);
            }
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
        return cameraRepository.findAll().stream()
                .filter(c -> c.getStatus() == CameraStatus.ACTIVE && c.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparing(Camera::getCameraCode))
                .map(c -> CameraSimpleResponse.builder()
                        .id(c.getId())
                        .cameraCode(c.getCameraCode())
                        .name(c.getName())
                        .status(c.getStatus())
                        .operationalStatus(c.getOperationalStatus())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CameraDetailResponse getCameraDetail(UUID id) {
        log.info("Fetching camera detail for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));
        return mapToDetailResponse(camera);
    }

    /**
     * Tự động kiểm tra trạng thái luồng video từ MediaMTX và cập nhật operational_status cho các camera đang ACTIVE
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    @Transactional
    public void syncLiveOperationalStatuses() {
        try {
            java.util.Map<String, Boolean> liveStatuses = mediaMtxService.getLivePathStatuses();
            if (liveStatuses == null || liveStatuses.isEmpty()) {
                return;
            }

            List<Camera> activeCameras = cameraRepository.findAll().stream()
                    .filter(c -> c.getStatus() == CameraStatus.ACTIVE)
                    .collect(Collectors.toList());

            for (Camera cam : activeCameras) {
                String pathName = mediaMtxService.formatPathName(cam.getCameraCode());
                boolean hasPathInMediaMtx = liveStatuses.containsKey(pathName);
                boolean isReady = Boolean.TRUE.equals(liveStatuses.get(pathName));
                OperationalStatus currentStatus = cam.getOperationalStatus();

                OperationalStatus newStatus;
                if (isReady) {
                    newStatus = OperationalStatus.ONLINE;
                } else if (hasPathInMediaMtx && currentStatus == OperationalStatus.ONLINE) {
                    newStatus = OperationalStatus.ONLINE;
                } else {
                    newStatus = OperationalStatus.OFFLINE;
                }

                if (currentStatus != newStatus) {
                    cam.setOperationalStatus(newStatus);
                    cameraRepository.save(cam);

                    CameraHealthLog healthLog = CameraHealthLog.builder()
                            .camera(cam)
                            .status(newStatus)
                            .checkedAt(OffsetDateTime.now())
                            .errorMessage(newStatus == OperationalStatus.ONLINE
                                    ? "Kết nối luồng RTSP thành công qua MediaMTX"
                                    : "Mất tín hiệu luồng RTSP từ camera")
                            .build();
                    cameraHealthLogRepository.save(healthLog);
                    log.info("Cập nhật trạng thái kết nối camera [{}]: {} -> {}", cam.getCameraCode(), currentStatus, newStatus);
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi đồng bộ trạng thái kết nối từ MediaMTX: {}", e.getMessage());
        }
    }

    public CameraDetailResponse updateCamera(UUID id, UpdateCameraRequest request) {
        log.info("Updating camera basic details for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

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

    public CameraDetailResponse decommissionCamera(UUID id) {
        log.info("Decommissioning camera for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        camera.setStatus(CameraStatus.DECOMMISSIONED);
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setArea(null); // BR-CAM-06: Tự động hủy gán khỏi khu vực khi decommission

        Camera saved = cameraRepository.save(camera);
        mediaMtxService.deleteCameraPath(camera.getCameraCode());
        return mapToDetailResponse(saved);
    }

    public void deleteCamera(UUID id) {
        log.info("Soft deleting camera for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        camera.setStatus(CameraStatus.DECOMMISSIONED);
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setDeletedAt(OffsetDateTime.now());
        camera.setArea(null);

        cameraRepository.save(camera);
        mediaMtxService.deleteCameraPath(camera.getCameraCode());
    }

    public CameraDetailResponse reactivateCamera(UUID id) {
        log.info("Reactivating camera for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        camera.setStatus(CameraStatus.ACTIVE);
        camera.setDeletedAt(null);

        Camera saved = cameraRepository.save(camera);
        cameraStreamConfigurationRepository.findByCameraId(id).ifPresent(cfg -> {
            String rtspUrl = buildRtspUrl(cfg);
            if (rtspUrl != null) {
                mediaMtxService.syncCameraPath(camera.getCameraCode(), rtspUrl);
            }
        });
        return mapToDetailResponse(saved);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAllStreamsToMediaMtxOnStartup() {
        log.info("Checking and syncing active camera stream paths to MediaMTX on startup...");
        try {
            var configs = cameraStreamConfigurationRepository.findAll();
            int syncedCount = 0;
            for (CameraStreamConfiguration cfg : configs) {
                if (cfg.getCamera() != null && cfg.getCamera().getStatus() == CameraStatus.ACTIVE) {
                    String rtspUrl = buildRtspUrl(cfg);
                    if (rtspUrl != null) {
                        mediaMtxService.syncCameraPath(cfg.getCamera().getCameraCode(), rtspUrl);
                        syncedCount++;
                    }
                }
            }
            log.info("MediaMTX startup sync completed: {} active camera stream(s) processed.", syncedCount);

            List<Camera> decommissionedCameras = cameraRepository.findAll().stream()
                    .filter(c -> c.getStatus() == CameraStatus.DECOMMISSIONED)
                    .toList();
            for (Camera decommCam : decommissionedCameras) {
                mediaMtxService.deleteCameraPath(decommCam.getCameraCode());
            }
        } catch (Exception e) {
            log.warn("Failed to sync camera streams to MediaMTX on startup: {}", e.getMessage());
        }
    }

    // === Configuration Upsert ===

    public CameraStreamConfigResponse upsertStreamConfig(UUID cameraId, CameraStreamConfigRequest req) {
        log.info("Upserting stream configuration for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        CameraStreamConfiguration config = cameraStreamConfigurationRepository.findByCameraId(cameraId)
                .orElse(CameraStreamConfiguration.builder().camera(camera).build());

        String rawPassword = req.getEffectivePassword();
        String effectivePlainPassword = rawPassword;
        if ((rawPassword == null || rawPassword.isBlank()) && config.getCredentialRef() != null && !config.getCredentialRef().isBlank()) {
            effectivePlainPassword = aesEncryptionUtil.decrypt(config.getCredentialRef());
        }

        String rtspUrl = buildRtspUrlWithCredentials(
                req.getHost(),
                req.getPort(),
                req.getUsername(),
                effectivePlainPassword,
                req.getMainStreamPath()
        );

        if (rtspUrl != null && camera.getStatus() == CameraStatus.ACTIVE) {
            mediaMtxService.syncCameraPathStrict(camera.getCameraCode(), rtspUrl);
        }

        if (rawPassword != null && !rawPassword.isBlank()) {
            config.setCredentialRef(aesEncryptionUtil.encrypt(rawPassword));
        }

        config.setHost(req.getHost().trim());
        config.setPort(req.getPort());
        config.setUsername(req.getUsername() != null ? req.getUsername().trim() : null);
        config.setMainStreamPath(req.getMainStreamPath().trim());
        config.setSubStreamPath(req.getSubStreamPath() != null ? req.getSubStreamPath().trim() : null);
        config.setRetryTimeBeforeAlerting(req.getRetryTimeBeforeAlerting() != null ? req.getRetryTimeBeforeAlerting() : 3);
        config.setTimeoutMs(req.getTimeoutMs() != null ? req.getTimeoutMs() : 5000);

        CameraStreamConfiguration saved = cameraStreamConfigurationRepository.save(config);
        return mapToStreamResponse(saved);
    }

    // === Health Logs ===

    @Transactional(readOnly = true)
    public Page<CameraHealthLogResponse> getHealthLogs(UUID cameraId, Pageable pageable) {
        log.info("Fetching health logs for camera id: {}", cameraId);
        if (!cameraRepository.existsById(cameraId)) {
            throw new CameraException(CameraErrorCode.ERR_CAM_002);
        }
        Page<CameraHealthLog> logs = cameraHealthLogRepository.findByCameraIdOrderByCheckedAtDesc(cameraId, pageable);
        return logs.map(this::mapToHealthLogResponse);
    }

    @Transactional(readOnly = true)
    public List<AreaSimpleResponse> getCameraAreas(UUID cameraId) {
        log.info("Fetching areas for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        if (camera.getArea() == null || camera.getArea().getDeletedAt() != null) {
            return List.of();
        }
        Area a = camera.getArea();
        return List.of(new AreaSimpleResponse(
                a.getId(),
                a.getName(),
                a.getAreaLevel(),
                a.getBuilding(),
                a.getFloor()
        ));
    }

    public CameraDetailResponse updateRoiGeometry(UUID cameraId, RoiUpdateRequest request) {
        log.info("Updating ROI geometry for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        RoiGeometry roi = request.getRoiGeometry();
        roiGeometryValidator.validate(roi);

        if (request.getSnapshotBase64() != null && !request.getSnapshotBase64().isBlank()) {
            try {
                String cleanBase64 = request.getSnapshotBase64();
                if (cleanBase64.contains(",")) {
                    cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
                }
                byte[] imageBytes = java.util.Base64.getDecoder().decode(cleanBase64);
                String snapshotUrl = minioStorageService.uploadRoiReferenceSnapshot(imageBytes, camera.getCameraCode());
                roi.setReferenceSnapshotUrl(snapshotUrl);
                roi.setReferenceSnapshotWidth(request.getSnapshotWidth() != null ? request.getSnapshotWidth() : 1920);
                roi.setReferenceSnapshotHeight(request.getSnapshotHeight() != null ? request.getSnapshotHeight() : 1080);
                roi.setReferenceCapturedAt(OffsetDateTime.now());
            } catch (Exception e) {
                log.error("Failed to upload reference snapshot to MinIO for camera {}: {}", camera.getCameraCode(), e.getMessage());
            }
        } else if (camera.getRoiGeometry() != null) {
            roi.setReferenceSnapshotUrl(camera.getRoiGeometry().getReferenceSnapshotUrl());
            roi.setReferenceSnapshotWidth(request.getSnapshotWidth() != null ? request.getSnapshotWidth() : camera.getRoiGeometry().getReferenceSnapshotWidth());
            roi.setReferenceSnapshotHeight(request.getSnapshotHeight() != null ? request.getSnapshotHeight() : camera.getRoiGeometry().getReferenceSnapshotHeight());
            roi.setReferenceCapturedAt(camera.getRoiGeometry().getReferenceCapturedAt());
        }

        camera.setRoiGeometry(roi);
        camera.setUpdatedAt(OffsetDateTime.now());
        Camera saved = cameraRepository.save(camera);

        syncRoiToAiService(camera.getCameraCode(), saved.getRoiGeometry());

        return mapToDetailResponse(saved);
    }

    private void syncRoiToAiService(String cameraCode, RoiGeometry roi) {
        if (aiServiceUrl == null || aiServiceUrl.isBlank() || roi == null) {
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

            List<Map<String, Object>> entryLinesList = new ArrayList<>();
            if (roi.getEntryLines() != null) {
                for (RoiGeometry.EntryLine l : roi.getEntryLines()) {
                    Map<String, Object> lineMap = new HashMap<>();
                    lineMap.put("label", l.getLabel() != null ? l.getLabel() : "");
                    lineMap.put("direction", l.getDirection() != null ? l.getDirection() : "AB_IS_IN");
                    if (l.getPointA() != null && l.getPointA().getX() != null && l.getPointA().getY() != null) {
                        lineMap.put("point_a", Map.of(
                                "x", l.getPointA().getX().doubleValue(),
                                "y", l.getPointA().getY().doubleValue()
                        ));
                    }
                    if (l.getPointB() != null && l.getPointB().getX() != null && l.getPointB().getY() != null) {
                        lineMap.put("point_b", Map.of(
                                "x", l.getPointB().getX().doubleValue(),
                                "y", l.getPointB().getY().doubleValue()
                        ));
                    }
                    entryLinesList.add(lineMap);
                }
            }

            Map<String, Object> body = new HashMap<>();
            body.put("camera_code", cameraCode);
            body.put("after_hour_start", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START));
            body.put("after_hour_end", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END));
            body.put("reference_width", roi.getReferenceSnapshotWidth() != null ? roi.getReferenceSnapshotWidth() : 1920);
            body.put("reference_height", roi.getReferenceSnapshotHeight() != null ? roi.getReferenceSnapshotHeight() : 1080);
            body.put("polygons", polygonsList);
            body.put("entry_lines", entryLinesList);

            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(endpoint, httpEntity, Map.class);
            log.info("Successfully synced ROI geometry to AI Service for camera [{}]", cameraCode);
        } catch (Exception e) {
            log.warn("Could not sync ROI geometry to AI Service for camera [{}]: {}", cameraCode, e.getMessage());
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
                .build();
    }

    private CameraStreamConfigResponse mapToStreamResponse(CameraStreamConfiguration config) {
        String cameraCode = (config.getCamera() != null) ? config.getCamera().getCameraCode() : "";
        String pathName = mediaMtxService.formatPathName(cameraCode);
        String whepUrl = (!pathName.isEmpty()) ? "http://localhost:8889/" + pathName + "/whep" : null;

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
                .retryTimeBeforeAlerting(config.getRetryTimeBeforeAlerting())
                .timeoutMs(config.getTimeoutMs())
                .updatedAt(config.getCamera() != null ? config.getCamera().getUpdatedAt() : null)
                .build();
    }

    private CameraHealthLogResponse mapToHealthLogResponse(CameraHealthLog log) {
        return CameraHealthLogResponse.builder()
                .id(log.getId())
                .status(log.getStatus())
                .checkedAt(log.getCheckedAt())
                .latencyMs(log.getLatencyMs())
                .fps(log.getFps())
                .errorCode(log.getErrorCode())
                .errorMessage(log.getErrorMessage())
                .build();
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

    private String buildRtspUrlWithCredentials(String host, Integer port, String username, String password, String path) {
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
}
