package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.ConnectStreamResponse;
import com.fa26se040.icss.dto.camera.TestConnectionResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraHealthLog;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CameraSnapshotService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final CameraHealthLogRepository healthLogRepo;
    private final CameraService cameraService;
    private final MediaMtxService mediaMtxService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    /**
     * "Kết nối": Build RTSP URL -> Gọi AI-service trích xuất snapshot frame.
     * Khi thành công: đồng bộ MediaMTX path, cập nhật operationalStatus = ONLINE, ghi health log và trả về base64 snapshot.
     * Khi thất bại: cập nhật operationalStatus = OFFLINE, ghi health log báo lỗi.
     */
    @Transactional
    public ConnectStreamResponse connectAndCapture(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        CameraStreamConfiguration config = streamConfigRepo.findByCameraId(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_STREAM_001));

        String rtspUrl = cameraService.buildRtspUrl(config);
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_001);
        }

        try {
            String aiEndpoint = aiServiceUrl + "/api/v1/cameras/snapshot";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            int timeout = (config.getTimeoutMs() != null && config.getTimeoutMs() > 0) ? config.getTimeoutMs() : 5000;
            Map<String, Object> body = Map.of(
                    "rtsp_url", rtspUrl,
                    "timeout_ms", timeout
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(aiEndpoint, request, Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                camera.setOperationalStatus(OperationalStatus.OFFLINE);
                camera.setUpdatedAt(OffsetDateTime.now());
                cameraRepository.save(camera);

                CameraHealthLog healthLog = CameraHealthLog.builder()
                        .camera(camera)
                        .status(OperationalStatus.OFFLINE)
                        .checkedAt(OffsetDateTime.now())
                        .errorMessage("Không thể trích xuất khung hình từ RTSP stream")
                        .build();
                healthLogRepo.save(healthLog);

                return ConnectStreamResponse.builder()
                        .success(false)
                        .operationalStatus(OperationalStatus.OFFLINE)
                        .errorMessage("Không thể trích xuất khung hình từ RTSP stream")
                        .build();
            }

            // Đồng bộ sang MediaMTX Gateway để đảm bảo path đã được đăng ký
            mediaMtxService.syncCameraPath(camera.getCameraCode(), rtspUrl);

            // Cập nhật operationalStatus = ONLINE
            camera.setOperationalStatus(OperationalStatus.ONLINE);
            camera.setUpdatedAt(OffsetDateTime.now());
            cameraRepository.save(camera);

            Number latencyNumber = (Number) response.get("latency_ms");
            Integer latency = latencyNumber != null ? latencyNumber.intValue() : null;

            // Ghi nhật ký sức khỏe (health log)
            CameraHealthLog healthLog = CameraHealthLog.builder()
                    .camera(camera)
                    .status(OperationalStatus.ONLINE)
                    .checkedAt(OffsetDateTime.now())
                    .latencyMs(latency)
                    .errorMessage("Kết nối RTSP thành công qua AI-service snapshot")
                    .build();
            healthLogRepo.save(healthLog);

            return ConnectStreamResponse.builder()
                    .success(true)
                    .snapshotBase64((String) response.get("snapshot_base64"))
                    .width((Integer) response.get("width"))
                    .height((Integer) response.get("height"))
                    .latencyMs(latencyNumber != null ? latencyNumber.longValue() : null)
                    .operationalStatus(OperationalStatus.ONLINE)
                    .build();

        } catch (CameraException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("AI-service snapshot connection failed for camera [{}]: {}", cameraId, e.getMessage());

            camera.setOperationalStatus(OperationalStatus.OFFLINE);
            camera.setUpdatedAt(OffsetDateTime.now());
            cameraRepository.save(camera);

            CameraHealthLog healthLog = CameraHealthLog.builder()
                    .camera(camera)
                    .status(OperationalStatus.OFFLINE)
                    .checkedAt(OffsetDateTime.now())
                    .errorMessage("Kết nối RTSP thất bại: " + e.getMessage())
                    .build();
            healthLogRepo.save(healthLog);

            return ConnectStreamResponse.builder()
                    .success(false)
                    .operationalStatus(OperationalStatus.OFFLINE)
                    .errorMessage("Không thể kết nối RTSP stream: " + e.getMessage())
                    .build();
        }
    }

    /**
     * "Thử kết nối": Chỉ kiểm tra kết nối luồng RTSP qua AI-service.
     * KHÔNG thay đổi operationalStatus, KHÔNG ghi health log, KHÔNG lưu snapshot.
     */
    @Transactional(readOnly = true)
    public TestConnectionResponse testConnection(UUID cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        CameraStreamConfiguration config = streamConfigRepo.findByCameraId(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_STREAM_001));

        String rtspUrl = cameraService.buildRtspUrl(config);
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_001);
        }

        try {
            String aiEndpoint = aiServiceUrl + "/api/v1/cameras/snapshot";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            int timeout = (config.getTimeoutMs() != null && config.getTimeoutMs() > 0) ? config.getTimeoutMs() : 5000;
            Map<String, Object> body = Map.of(
                    "rtsp_url", rtspUrl,
                    "timeout_ms", timeout
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(aiEndpoint, request, Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                return TestConnectionResponse.builder()
                        .success(false)
                        .message("Không thể kết nối đến luồng RTSP của camera")
                        .build();
            }

            Number latencyNumber = (Number) response.get("latency_ms");
            Long latency = latencyNumber != null ? latencyNumber.longValue() : null;

            return TestConnectionResponse.builder()
                    .success(true)
                    .latencyMs(latency)
                    .message("Kết nối thành công tới luồng RTSP (Độ trễ: " + (latency != null ? latency + "ms" : "N/A") + ")")
                    .build();

        } catch (CameraException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("AI-service test connection failed for camera [{}]: {}", cameraId, e.getMessage());
            return TestConnectionResponse.builder()
                    .success(false)
                    .message("Kiểm tra kết nối thất bại: " + e.getMessage())
                    .build();
        }
    }
}
