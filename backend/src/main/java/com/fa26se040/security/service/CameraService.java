package com.fa26se040.security.service;

import com.fa26se040.security.dto.camera.*;
import com.fa26se040.security.entity.*;
import com.fa26se040.security.exception.DuplicateResourceException;
import com.fa26se040.security.exception.ResourceNotFoundException;
import com.fa26se040.security.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CameraService {

    private final CameraRepository cameraRepository;
    private final CameraSpecificationRepository cameraSpecificationRepository;
    private final CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    private final CameraAIConfigurationRepository cameraAIConfigurationRepository;
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
                .floor(request.getFloor())
                .zoneName(request.getZoneName())
                .x(request.getX())
                .y(request.getY())
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
        Page<Camera> cameras = cameraRepository.findFiltered(search, status, opStatus, pageable);
        return cameras.map(this::mapToListResponse);
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
        camera.setFloor(request.getFloor());
        camera.setZoneName(request.getZoneName());
        camera.setX(request.getX());
        camera.setY(request.getY());
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
        spec.setPtzSupported(req.getPtzSupported());
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

        config.setProtocol(req.getProtocol());
        config.setHost(req.getHost());
        config.setPort(req.getPort());
        config.setUsername(req.getUsername());
        config.setCredentialRef(req.getCredentialRef());
        config.setMainStreamUrl(req.getMainStreamUrl());
        config.setSubStreamUrl(req.getSubStreamUrl());
        config.setStreamEnabled(req.getStreamEnabled() != null ? req.getStreamEnabled() : true);
        config.setReconnectEnabled(req.getReconnectEnabled() != null ? req.getReconnectEnabled() : true);
        config.setTimeoutMs(req.getTimeoutMs() != null ? req.getTimeoutMs() : 5000);

        CameraStreamConfiguration saved = cameraStreamConfigurationRepository.save(config);
        return mapToStreamResponse(saved);
    }

    public CameraAIConfigResponse upsertAIConfig(UUID cameraId, CameraAIConfigRequest req) {
        log.info("Upserting AI configuration for camera id: {}", cameraId);
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new ResourceNotFoundException("Camera not found with id: " + cameraId));

        CameraAIConfiguration config = cameraAIConfigurationRepository.findByCameraId(cameraId)
                .orElse(CameraAIConfiguration.builder().camera(camera).build());

        config.setPersonDetectionEnabled(req.getPersonDetectionEnabled() != null ? req.getPersonDetectionEnabled() : false);
        config.setFaceRecognitionEnabled(req.getFaceRecognitionEnabled() != null ? req.getFaceRecognitionEnabled() : false);
        config.setLoiteringDetectionEnabled(req.getLoiteringDetectionEnabled() != null ? req.getLoiteringDetectionEnabled() : false);
        config.setFaceMatchThreshold(req.getFaceMatchThreshold());
        config.setLoiteringThresholdSeconds(req.getLoiteringThresholdSeconds());
        config.setInferenceFps(req.getInferenceFps());
        config.setModelVersion(req.getModelVersion());

        CameraAIConfiguration saved = cameraAIConfigurationRepository.save(config);
        return mapToAIResponse(saved);
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
                .floor(camera.getFloor())
                .zoneName(camera.getZoneName())
                .status(camera.getStatus())
                .operationalStatus(camera.getOperationalStatus())
                .build();
    }

    private CameraDetailResponse mapToDetailResponse(Camera camera) {
        return CameraDetailResponse.builder()
                .id(camera.getId())
                .cameraCode(camera.getCameraCode())
                .name(camera.getName())
                .floor(camera.getFloor())
                .zoneName(camera.getZoneName())
                .x(camera.getX())
                .y(camera.getY())
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
                .aiConfig(camera.getAiConfiguration() != null ? mapToAIResponse(camera.getAiConfiguration()) : null)
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
                .ptzSupported(spec.getPtzSupported())
                .weatherProof(spec.getWeatherProof())
                .firmwareVersion(spec.getFirmwareVersion())
                .build();
    }

    private CameraStreamConfigResponse mapToStreamResponse(CameraStreamConfiguration config) {
        return CameraStreamConfigResponse.builder()
                .id(config.getId())
                .protocol(config.getProtocol())
                .host(config.getHost())
                .port(config.getPort())
                .username(config.getUsername())
                .credentialRef(config.getCredentialRef())
                .mainStreamUrl(config.getMainStreamUrl())
                .subStreamUrl(config.getSubStreamUrl())
                .streamEnabled(config.getStreamEnabled())
                .reconnectEnabled(config.getReconnectEnabled())
                .timeoutMs(config.getTimeoutMs())
                .build();
    }

    private CameraAIConfigResponse mapToAIResponse(CameraAIConfiguration config) {
        return CameraAIConfigResponse.builder()
                .id(config.getId())
                .personDetectionEnabled(config.getPersonDetectionEnabled())
                .faceRecognitionEnabled(config.getFaceRecognitionEnabled())
                .loiteringDetectionEnabled(config.getLoiteringDetectionEnabled())
                .faceMatchThreshold(config.getFaceMatchThreshold())
                .loiteringThresholdSeconds(config.getLoiteringThresholdSeconds())
                .inferenceFps(config.getInferenceFps())
                .modelVersion(config.getModelVersion())
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
