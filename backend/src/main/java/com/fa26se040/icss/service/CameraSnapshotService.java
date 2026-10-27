package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraSnapshotResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service chuyên trách trích xuất khung hình tĩnh (Snapshot) từ Camera.
 * Tuân thủ quy tắc Pure Read-Only / Idempotent:
 * - KHÔNG thay đổi operationalStatus của Camera trong DB
 * - KHÔNG ghi bản ghi vào bảng CameraHealthLog
 * - KHÔNG kích hoạt AI Stream Worker
 * - KHÔNG thay đổi cấu hình MediaMTX Gateway
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraSnapshotService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final CameraService cameraService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${camera.rtsp.timeout-ms:10000}")
    private int defaultTimeoutMs;

    @Transactional(readOnly = true)
    public CameraSnapshotResponse captureSnapshot(UUID cameraId) {
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

        int timeout = defaultTimeoutMs;


        try {
            String aiEndpoint = aiServiceUrl + "/api/v1/cameras/snapshot";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                    "rtsp_url", rtspUrl,
                    "timeout_ms", timeout
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(aiEndpoint, request, Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                log.warn("AI-service trích xuất snapshot thất bại cho camera [{}]", camera.getCameraCode());
                throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
            }

            Number latencyNumber = (Number) response.get("latency_ms");
            Long latency = latencyNumber != null ? latencyNumber.longValue() : null;

            return CameraSnapshotResponse.builder()
                    .success(true)
                    .snapshotBase64((String) response.get("snapshot_base64"))
                    .width((Integer) response.get("width"))
                    .height((Integer) response.get("height"))
                    .latencyMs(latency)
                    .capturedAt(OffsetDateTime.now())
                    .build();

        } catch (CameraException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Lỗi ngoại lệ khi gọi AI-service snapshot cho camera [{}]: {}", camera.getCameraCode(), e.getMessage());
            throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
        }
    }
}
