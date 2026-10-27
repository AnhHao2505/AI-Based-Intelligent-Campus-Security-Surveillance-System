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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service chuyên trách quản lý vòng đời luồng phát video (Stream Lifecycle) và tiến trình phân tích AI.
 * Cung cấp 2 thao tác đối xứng:
 * - startStream: Kích hoạt MediaMTX path, bật AI Stream Worker, cập nhật operationalStatus = ONLINE, ghi health log.
 * - stopStream: Xóa MediaMTX path, dừng AI Stream Worker, cập nhật operationalStatus = OFFLINE, ghi health log.
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
    private final RestTemplate restTemplate = new RestTemplate();

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

        // 1. Đăng ký luồng sang MediaMTX Gateway (WHEP/HLS)
        mediaMtxService.syncCameraPath(camera.getCameraCode(), rtspUrl);

        // 2. Kích hoạt background AI Stream Worker (nếu có cấu hình AI Service) qua MediaMTX Gateway
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
            log.info("✅ Đã kích hoạt AI Stream Worker cho camera [{}] qua MediaMTX Gateway ({})", camera.getCameraCode(), workerRtspUrl);
        } catch (Exception ex) {
            log.warn("⚠️ Không thể kích hoạt AI Stream Worker cho camera [{}]: {}", camera.getCameraCode(), ex.getMessage());
        }

        // 3. Cập nhật operationalStatus = ONLINE
        camera.setOperationalStatus(OperationalStatus.ONLINE);
        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        // 4. Ghi nhật ký sức khỏe CameraHealthLog
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

        // 1. Xóa path khỏi MediaMTX Gateway để giải phóng kết nối
        mediaMtxService.deleteCameraPath(camera.getCameraCode());

        // 2. Dừng background AI Stream Worker
        try {
            String streamStopUrl = aiServiceUrl + "/api/v1/stream/stop?camera_code=" + camera.getCameraCode();
            restTemplate.postForObject(streamStopUrl, null, Map.class);
            log.info("✅ Đã dừng AI Stream Worker cho camera [{}]", camera.getCameraCode());
        } catch (Exception ex) {
            log.debug("Không thể dừng AI Stream Worker cho camera [{}]: {}", camera.getCameraCode(), ex.getMessage());
        }

        // 3. Cập nhật operationalStatus = OFFLINE
        camera.setOperationalStatus(OperationalStatus.OFFLINE);
        camera.setUpdatedAt(OffsetDateTime.now());
        cameraRepository.save(camera);

        // 4. Ghi nhật ký sức khỏe CameraHealthLog
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

    /**
     * Tự động phục hồi luồng MediaMTX và kích hoạt AI Stream Worker (bao gồm ghi hình định kỳ)
     * cho tất cả camera đang ở trạng thái ONLINE khi Backend khởi động lại.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoRecoverOnlineStreams() {
        log.info("🔄 [StreamRecovery] Tự động kiểm tra và phục hồi luồng camera ONLINE khi hệ thống khởi động...");
        try {
            List<Camera> onlineCameras = cameraRepository.findAll().stream()
                    .filter(c -> c.getStatus() == CameraStatus.ACTIVE && c.getOperationalStatus() == OperationalStatus.ONLINE)
                    .toList();
            for (Camera cam : onlineCameras) {
                try {
                    log.info("🔄 [StreamRecovery] Đang kích hoạt lại luồng và AI worker cho camera [{}]...", cam.getCameraCode());
                    startStream(cam.getId());
                } catch (Exception ex) {
                    log.warn("⚠️ [StreamRecovery] Không thể tự động phục hồi luồng cho camera [{}]: {}", cam.getCameraCode(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("⚠️ [StreamRecovery] Lỗi khi quét danh sách camera phục hồi: {}", ex.getMessage());
        }
    }
}
