package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fa26se040.icss.dto.camera.*;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CameraService {

    private final CameraRepository cameraRepository;
    private final CameraSpecificationRepository cameraSpecificationRepository;
    private final CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    private final CameraHealthLogRepository cameraHealthLogRepository;

    // === Camera CRUD ===

    public CameraDetailResponse createCamera(CreateCameraRequest request) {
        log.info("Creating camera with name: {}", request.getName());

        String cameraCode = request.getCameraCode();
        if (cameraCode != null && !cameraCode.trim().isEmpty()) {
            if (cameraRepository.existsByCameraCode(cameraCode)) {
                throw new DuplicateResourceException("Camera code '" + cameraCode + "' already exists");
            }
        } else {
            cameraCode = generateCameraCode();
            log.info("Auto-generated camera code: {}", cameraCode);
        }

        Camera camera = Camera.builder()
                .cameraCode(cameraCode)
                .name(request.getName())
                .mountingHeight(request.getMountingHeight())
                .orientation(request.getOrientation())
                .tiltAngle(request.getTiltAngle())
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .build();

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<CameraListResponse> listCameras(String search, CameraStatus status,
                                                OperationalStatus opStatus, Pageable pageable) {
        log.info("Listing cameras with search: {}, status: {}, operationalStatus: {}", search, status, opStatus);
        String searchParam = "%" + (search != null ? search.trim().toLowerCase() : "") + "%";
        Page<Camera> cameras = cameraRepository.findFiltered(searchParam, status, opStatus, pageable);
        return cameras.map(this::mapToListResponse);
    }

    @Transactional(readOnly = true)
    public List<CameraSimpleResponse> getAllActiveSimple() {
        log.info("Fetching simple list of active cameras");
        return cameraRepository.findAll().stream()
                .filter(c -> c.getStatus() == CameraStatus.ACTIVE)
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
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + id));
        return mapToDetailResponse(camera);
    }

    public CameraDetailResponse updateCamera(UUID id, UpdateCameraRequest request) {
        log.info("Updating camera basic details for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + id));

        camera.setName(request.getName());
        camera.setMountingHeight(request.getMountingHeight());
        camera.setOrientation(request.getOrientation());
        camera.setTiltAngle(request.getTiltAngle());
        camera.setInstalledAt(request.getInstalledAt());

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    public CameraDetailResponse decommissionCamera(UUID id) {
        log.info("Decommissioning camera for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + id));

        camera.setStatus(CameraStatus.DECOMMISSIONED);
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setDeletedAt(OffsetDateTime.now());

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    public CameraDetailResponse reactivateCamera(UUID id) {
        log.info("Reactivating camera for id: {}", id);
        Camera camera = cameraRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + id));

        camera.setStatus(CameraStatus.ACTIVE);
        camera.setDeletedAt(null);

        Camera saved = cameraRepository.save(camera);
        return mapToDetailResponse(saved);
    }

    // === Configuration Upsert ===

    public CameraSpecificationResponse upsertSpecification(UUID cameraId, CameraSpecificationRequest req) {
        log.info("Upserting specification for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + cameraId));

        CameraSpecification spec = cameraSpecificationRepository.findByCameraId(cameraId)
                .orElse(CameraSpecification.builder().camera(camera).build());

        spec.setManufacturer(req.getManufacturer());
        spec.setModel(req.getModel());
        spec.setSerialNumber(req.getSerialNumber());
        spec.setResolution(req.getResolution());
        spec.setFps(req.getFps());
        spec.setLens(req.getLens());
        spec.setFocalLength(req.getFocalLength());
        spec.setFieldOfView(req.getFieldOfView());
        spec.setNightVision(req.getNightVision());
        spec.setWeatherProof(req.getWeatherProof());
        spec.setFirmwareVersion(req.getFirmwareVersion());

        CameraSpecification saved = cameraSpecificationRepository.save(spec);
        return mapToSpecResponse(saved);
    }

    public CameraStreamConfigResponse upsertStreamConfig(UUID cameraId, CameraStreamConfigRequest req) {
        log.info("Upserting stream configuration for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + cameraId));

        CameraStreamConfiguration config = cameraStreamConfigurationRepository.findByCameraId(cameraId)
                .orElse(CameraStreamConfiguration.builder().camera(camera).build());

        config.setHost(req.getHost());
        config.setPort(req.getPort());
        config.setUsername(req.getUsername());
        config.setCredentialRef(req.getCredentialRef());
        config.setMainStreamPath(req.getMainStreamPath());
        config.setSubStreamPath(req.getSubStreamPath());
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
            throw new ResourceNotFoundException("Camera not found with id: " + cameraId);
        }
        Page<CameraHealthLog> logs = cameraHealthLogRepository.findByCameraIdOrderByCheckedAtDesc(cameraId, pageable);
        return logs.map(this::mapToHealthLogResponse);
    }

    // === Helpers & Mapping ===

    private String generateCameraCode() {
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
        return CameraListResponse.builder()
                .id(camera.getId())
                .cameraCode(camera.getCameraCode())
                .name(camera.getName())
                .status(camera.getStatus())
                .operationalStatus(camera.getOperationalStatus())
                .build();
    }

    private CameraDetailResponse mapToDetailResponse(Camera camera) {
        return CameraDetailResponse.builder()
                .id(camera.getId())
                .cameraCode(camera.getCameraCode())
                .name(camera.getName())
                .mountingHeight(camera.getMountingHeight())
                .orientation(camera.getOrientation())
                .tiltAngle(camera.getTiltAngle())
                .status(camera.getStatus())
                .operationalStatus(camera.getOperationalStatus())
                .installedAt(camera.getInstalledAt())
                .createdAt(camera.getCreatedAt())
                .updatedAt(camera.getUpdatedAt())
                .specification(camera.getSpecification() != null ? mapToSpecResponse(camera.getSpecification()) : null)
                .streamConfig(camera.getStreamConfiguration() != null ? mapToStreamResponse(camera.getStreamConfiguration()) : null)
                .build();
    }

    private CameraSpecificationResponse mapToSpecResponse(CameraSpecification spec) {
        return CameraSpecificationResponse.builder()
                .id(spec.getId())
                .manufacturer(spec.getManufacturer())
                .model(spec.getModel())
                .serialNumber(spec.getSerialNumber())
                .resolution(spec.getResolution())
                .fps(spec.getFps())
                .lens(spec.getLens())
                .focalLength(spec.getFocalLength())
                .fieldOfView(spec.getFieldOfView())
                .nightVision(spec.getNightVision())
                .weatherProof(spec.getWeatherProof())
                .firmwareVersion(spec.getFirmwareVersion())
                .build();
    }

    private CameraStreamConfigResponse mapToStreamResponse(CameraStreamConfiguration config) {
        return CameraStreamConfigResponse.builder()
                .id(config.getId())
                .host(config.getHost())
                .port(config.getPort())
                .username(config.getUsername())
                .credentialRef(config.getCredentialRef())
                .mainStreamPath(config.getMainStreamPath())
                .subStreamPath(config.getSubStreamPath())
                .retryTimeBeforeAlerting(config.getRetryTimeBeforeAlerting())
                .timeoutMs(config.getTimeoutMs())
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
}
