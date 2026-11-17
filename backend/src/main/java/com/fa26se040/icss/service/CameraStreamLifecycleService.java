package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamLifecycleResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fa26se040.icss.config.AiServiceRestTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quản lý vòng đời luồng phát video (Stream Lifecycle) và tiến trình phân tích AI theo kiến trúc Cloud-Edge:
 * - Khi MQTT Broker kết nối: Publish cấu hình camera bền vững (Retained=true) tới Edge, không gọi HTTP vào mạng nội bộ.
 * - Khi chạy cục bộ không có MQTT: Fallback về MediaMTX + AI Service REST API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraStreamLifecycleService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final CameraService cameraService;
    private final MediaMtxService mediaMtxService;
    private final AiServiceRestTemplate restTemplate;

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Autowired(required = false)
    private EdgeStatusService edgeStatusService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Transactional
    public CameraStreamLifecycleResponse startStream(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }

        Area area = camera.getArea();
        if (area != null && (area.getDeletedAt() != null || !Boolean.TRUE.equals(area.getIsActive()))) {
            throw new AreaException(AreaErrorCode.ERR_AREA_017);
        }

        CameraStreamConfiguration config = streamConfigRepo.findByCameraId(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_STREAM_001));

        String mainRtspUrl = cameraService.buildRtspUrl(config);
        if (mainRtspUrl == null || mainRtspUrl.isBlank()) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_001);
        }
        String subRtspUrl = cameraService.buildSubRtspUrl(config);
        boolean hasSub = (subRtspUrl != null && !subRtspUrl.isBlank());

        boolean mqttConnected = edgeMqttControlService != null && edgeMqttControlService.isConnected();
        boolean edgeOnline = mqttConnected && edgeStatusService != null && edgeStatusService.hasActiveOnlineEdge();
        boolean aiWorkerRunning = false;
        if (mqttConnected) {
            if (hasSub) {
                edgeMqttControlService.publishCameraStreamConfig(
                        camera.getCameraCode(),
                        subRtspUrl,
                        mainRtspUrl,
                        true,
                        camera.getRoiGeometry()
                );
            } else {
                edgeMqttControlService.publishCameraStreamConfig(
                        camera.getCameraCode(),
                        mainRtspUrl,
                        true,
                        camera.getRoiGeometry()
                );
            }
            log.info("📡 [Cloud-Edge] Đã đồng bộ kích hoạt stream & AI Worker cho camera [{}] qua MQTT Retained Config (edgeOnline={})",
                    camera.getCameraCode(), edgeOnline);
            if (edgeOnline) {
                aiWorkerRunning = true;
            }
        }

        if (!edgeOnline) {
            // Fallback khi chạy Standalone Local hoặc chưa có Edge ONLINE (Gateway-First strict)
            if (hasSub) {
                mediaMtxService.syncCameraPathStrict(camera.getCameraCode(), subRtspUrl);
                mediaMtxService.syncCameraPathStrict(camera.getCameraCode() + "_main", mainRtspUrl);
                log.info("✅ [MediaMTX Dual-Stream] Đã đồng bộ luồng phụ [{}] (WHEP UI) và luồng chính [{}_main] (AI & Recording)",
                        camera.getCameraCode(), camera.getCameraCode());
            } else {
                mediaMtxService.syncCameraPathStrict(camera.getCameraCode(), mainRtspUrl);
            }

            MediaMtxService.PathReadinessState readiness = mediaMtxService.awaitPathReady(camera.getCameraCode(), 3000L);
            if (readiness != MediaMtxService.PathReadinessState.READY) {
                mediaMtxService.deleteCameraPath(camera.getCameraCode());
                camera.setOperationalStatus(OperationalStatus.OFFLINE);
                camera.setUpdatedAt(OffsetDateTime.now());
                cameraRepository.save(camera);
                log.warn("❌ [MediaMTX] Camera [{}] chưa sẵn sàng sau khi kết nối RTSP (state={}) -> giữ OFFLINE",
                        camera.getCameraCode(), readiness);
                String failMsg = readiness == MediaMtxService.PathReadinessState.UNAVAILABLE
                        ? "Không thể xác minh trạng thái luồng từ MediaMTX Gateway."
                        : "MediaMTX chưa nhận được luồng video từ camera. Kiểm tra thiết bị phát RTSP, đường dẫn hoặc tài khoản.";
                return CameraStreamLifecycleResponse.builder()
                        .success(false)
                        .cameraId(camera.getId())
                        .cameraCode(camera.getCameraCode())
                        .operationalStatus(OperationalStatus.OFFLINE)
                        .aiWorkerRunning(false)
                        .message(failMsg)
                        .timestamp(OffsetDateTime.now())
                        .build();
            }

            if (aiServiceUrl != null && !aiServiceUrl.isBlank()) {
                try {
                    String streamStartUrl = aiServiceUrl + "/api/v1/stream/start";
                    String workerRtspUrl = hasSub
                            ? mediaMtxService.getGatewayRtspUrl(camera.getCameraCode() + "_main")
                            : mediaMtxService.getGatewayRtspUrl(camera.getCameraCode());
                    Map<String, Object> reqBody = new HashMap<>();
                    reqBody.put("camera_code", camera.getCameraCode());
                    reqBody.put("rtsp_url", workerRtspUrl);
                    if (camera.getRoiGeometry() != null) {
                        reqBody.put("roi_geometry", camera.getRoiGeometry());
                    }
                    restTemplate.postForObject(streamStartUrl, reqBody, Map.class);
                    aiWorkerRunning = true;
                    log.info("✅ Đã kích hoạt AI Stream Worker cho camera [{}] qua MediaMTX Gateway ({})",
                            camera.getCameraCode(), MediaMtxService.maskRtspCredentials(workerRtspUrl));
                } catch (Exception ex) {
                    aiWorkerRunning = false;
                    log.warn("⚠️ Không thể kích hoạt AI Stream Worker cho camera [{}]: {}",
                            camera.getCameraCode(), ex.getMessage());
                }
            }
        }

        camera.setOperationalStatus(OperationalStatus.ONLINE);
        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        String successMsg = aiWorkerRunning
                ? "Kết nối Stream phát trực tiếp thành công"
                : "Kết nối Stream phát trực tiếp thành công (Cảnh báo: AI Worker chưa sẵn sàng)";

        return CameraStreamLifecycleResponse.builder()
                .success(true)
                .cameraId(camera.getId())
                .cameraCode(camera.getCameraCode())
                .operationalStatus(OperationalStatus.ONLINE)
                .aiWorkerRunning(aiWorkerRunning)
                .message(successMsg)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    @Transactional
    public CameraStreamLifecycleResponse stopStream(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        boolean mqttConnected = edgeMqttControlService != null && edgeMqttControlService.isConnected();
        boolean edgeOnline = mqttConnected && edgeStatusService != null && edgeStatusService.hasActiveOnlineEdge();
        if (mqttConnected) {
            edgeMqttControlService.publishCameraStreamConfig(
                    camera.getCameraCode(),
                    "",
                    false,
                    camera.getRoiGeometry()
            );
            log.info("📡 [Cloud-Edge] Đã gửi lệnh ngắt stream camera [{}] qua MQTT Retained Config (edgeOnline={})",
                    camera.getCameraCode(), edgeOnline);
        }

        if (!edgeOnline) {
            mediaMtxService.deleteCameraPath(camera.getCameraCode());
            if (aiServiceUrl != null && !aiServiceUrl.isBlank()) {
                try {
                    String streamStopUrl = aiServiceUrl + "/api/v1/stream/stop?camera_code=" + camera.getCameraCode();
                    restTemplate.postForObject(streamStopUrl, null, Map.class);
                    log.info("✅ Đã dừng AI Stream Worker cho camera [{}]", camera.getCameraCode());
                } catch (Exception ex) {
                    log.debug("Không thể dừng AI Stream Worker cho camera [{}]: {}",
                            camera.getCameraCode(), ex.getMessage());
                }
            }
        }

        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        return CameraStreamLifecycleResponse.builder()
                .success(true)
                .cameraId(camera.getId())
                .cameraCode(camera.getCameraCode())
                .operationalStatus(OperationalStatus.OFFLINE)
                .aiWorkerRunning(false)
                .message("Đã Ngắt Stream phát trực tiếp của camera")
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public void requestCloudRelay(String cameraCode, boolean active) {
        if (cameraCode == null || cameraCode.isBlank()) {
            return;
        }
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishCloudRelayCommand(cameraCode.trim().toUpperCase(), active);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void autoSyncActiveCamerasOnStartup() {
        log.info("Checking and syncing active camera stream paths to MediaMTX / MQTT on startup...");
        try {
            List<CameraStreamConfiguration> allConfigs = streamConfigRepo.findAll();
            int syncedCount = 0;
            for (CameraStreamConfiguration cfg : allConfigs) {
                Camera cam = cfg.getCamera();
                if (cam == null || cam.getDeletedAt() != null || cam.getStatus() != CameraStatus.ACTIVE) {
                    continue;
                }
                String mainRtspUrl = cameraService.buildRtspUrl(cfg);
                if (mainRtspUrl == null || mainRtspUrl.isBlank()) {
                    continue;
                }
                String subRtspUrl = cameraService.buildSubRtspUrl(cfg);
                boolean hasSub = (subRtspUrl != null && !subRtspUrl.isBlank());

                if (hasSub) {
                    mediaMtxService.syncCameraPath(cam.getCameraCode(), subRtspUrl);
                    mediaMtxService.syncCameraPath(cam.getCameraCode() + "_main", mainRtspUrl);
                } else {
                    mediaMtxService.syncCameraPath(cam.getCameraCode(), mainRtspUrl);
                }

                if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                    boolean active = cam.getOperationalStatus() == OperationalStatus.ONLINE;
                    if (hasSub) {
                        edgeMqttControlService.publishCameraStreamConfig(
                                cam.getCameraCode(),
                                subRtspUrl,
                                mainRtspUrl,
                                active,
                                cam.getRoiGeometry()
                        );
                    } else {
                        edgeMqttControlService.publishCameraStreamConfig(
                                cam.getCameraCode(),
                                mainRtspUrl,
                                active,
                                cam.getRoiGeometry()
                        );
                    }
                }

                syncedCount++;
                if (cam.getOperationalStatus() == OperationalStatus.ONLINE && aiServiceUrl != null && !aiServiceUrl.isBlank()) {
                    try {
                        String streamStartUrl = aiServiceUrl + "/api/v1/stream/start";
                        String workerRtspUrl = hasSub
                                ? mediaMtxService.getGatewayRtspUrl(cam.getCameraCode() + "_main")
                                : mediaMtxService.getGatewayRtspUrl(cam.getCameraCode());
                        Map<String, Object> reqBody = new HashMap<>();
                        reqBody.put("camera_code", cam.getCameraCode());
                        reqBody.put("rtsp_url", workerRtspUrl);
                        if (cam.getRoiGeometry() != null) {
                            reqBody.put("roi_geometry", cam.getRoiGeometry());
                        }
                        restTemplate.postForObject(streamStartUrl, reqBody, Map.class);
                    } catch (Exception ex) {
                        log.debug("Không thể auto-start stream worker on startup cho {}: {}", cam.getCameraCode(), ex.getMessage());
                    }
                }
            }
            log.info("MediaMTX startup sync completed: {} active camera stream(s) processed.", syncedCount);

            List<Camera> decommissionedCameras = cameraRepository.findByStatus(CameraStatus.DECOMMISSIONED);
            for (Camera decommCam : decommissionedCameras) {
                mediaMtxService.deleteCameraPath(decommCam.getCameraCode());
                if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                    edgeMqttControlService.publishCameraStreamConfig(decommCam.getCameraCode(), "", false, null);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Không thể tự động đồng bộ cấu hình camera khi khởi động: {}", e.getMessage());
        }
    }
}
