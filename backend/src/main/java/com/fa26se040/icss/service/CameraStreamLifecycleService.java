package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamLifecycleResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraHealthLog;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
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
import org.springframework.web.client.RestTemplate;

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
    private final CameraHealthLogRepository healthLogRepo;
    private final CameraService cameraService;
    private final MediaMtxService mediaMtxService;
    private RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Transactional
    public CameraStreamLifecycleResponse startStream(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }

        CameraStreamConfiguration config = streamConfigRepo.findByCameraId(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_STREAM_001));

        String rtspUrl = cameraService.buildRtspUrl(config);
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_001);
        }

        boolean handledViaMqtt = false;
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishCameraStreamConfig(
                    camera.getCameraCode(),
                    rtspUrl,
                    true,
                    camera.getRoiGeometry()
            );
            handledViaMqtt = true;
            log.info("📡 [Cloud-Edge] Đã đồng bộ kích hoạt stream & AI Worker cho camera [{}] qua MQTT Retained Config",
                    camera.getCameraCode());
        }

        if (!handledViaMqtt) {
            // Fallback khi chạy Standalone Local không kết nối MQTT
            mediaMtxService.syncCameraPath(camera.getCameraCode(), rtspUrl);
            if (aiServiceUrl != null && !aiServiceUrl.isBlank()) {
                try {
                    String streamStartUrl = aiServiceUrl + "/api/v1/stream/start";
                    String workerRtspUrl = mediaMtxService.getGatewayRtspUrl(camera.getCameraCode());
                    Map<String, Object> reqBody = new HashMap<>();
                    reqBody.put("camera_code", camera.getCameraCode());
                    reqBody.put("rtsp_url", workerRtspUrl);
                    if (camera.getRoiGeometry() != null) {
                        reqBody.put("roi_geometry", camera.getRoiGeometry());
                    }
                    restTemplate.postForObject(streamStartUrl, reqBody, Map.class);
                    log.info("✅ Đã kích hoạt AI Stream Worker cho camera [{}] qua MediaMTX Gateway ({})",
                            camera.getCameraCode(), workerRtspUrl);
                } catch (Exception ex) {
                    log.warn("⚠️ Không thể kích hoạt AI Stream Worker cho camera [{}]: {}",
                            camera.getCameraCode(), ex.getMessage());
                }
            }
        }

        camera.setOperationalStatus(OperationalStatus.ONLINE);
        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        CameraHealthLog healthLog = CameraHealthLog.builder()
                .camera(camera)
                .status(OperationalStatus.ONLINE)
                .checkedAt(OffsetDateTime.now())
                .errorMessage("Kết nối Stream phát trực tiếp thành công")
                .build();
        healthLogRepo.save(healthLog);

        return CameraStreamLifecycleResponse.builder()
                .success(true)
                .cameraId(camera.getId())
                .cameraCode(camera.getCameraCode())
                .operationalStatus(OperationalStatus.ONLINE)
                .message("Kết nối Stream phát trực tiếp thành công")
                .timestamp(OffsetDateTime.now())
                .build();
    }

    @Transactional
    public CameraStreamLifecycleResponse stopStream(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        boolean handledViaMqtt = false;
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishCameraStreamConfig(
                    camera.getCameraCode(),
                    "",
                    false,
                    camera.getRoiGeometry()
            );
            handledViaMqtt = true;
            log.info("📡 [Cloud-Edge] Đã gửi lệnh ngắt stream camera [{}] qua MQTT Retained Config", camera.getCameraCode());
        }

        if (!handledViaMqtt) {
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

        CameraHealthLog healthLog = CameraHealthLog.builder()
                .camera(camera)
                .status(OperationalStatus.OFFLINE)
                .checkedAt(OffsetDateTime.now())
                .errorMessage("Ngắt Stream phát trực tiếp bởi người dùng")
                .build();
        healthLogRepo.save(healthLog);

        return CameraStreamLifecycleResponse.builder()
                .success(true)
                .cameraId(camera.getId())
                .cameraCode(camera.getCameraCode())
                .operationalStatus(OperationalStatus.OFFLINE)
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
        try {
            List<CameraStreamConfiguration> allConfigs = streamConfigRepo.findAll();
            for (CameraStreamConfiguration cfg : allConfigs) {
                Camera cam = cfg.getCamera();
                if (cam != null && cam.getStatus() == CameraStatus.ACTIVE
                        && cam.getOperationalStatus() == OperationalStatus.ONLINE) {
                    String rtspUrl = cameraService.buildRtspUrl(cfg);
                    if (rtspUrl != null && !rtspUrl.isBlank()
                            && edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                        edgeMqttControlService.publishCameraStreamConfig(
                                cam.getCameraCode(),
                                rtspUrl,
                                true,
                                cam.getRoiGeometry()
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Không thể tự động đồng bộ cấu hình camera lên MQTT khi khởi động: {}", e.getMessage());
        }
    }
}
