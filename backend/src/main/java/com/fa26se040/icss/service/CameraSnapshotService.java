package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraSnapshotResponse;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CameraSnapshotService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final CameraService cameraService;
    private RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Autowired(required = false)
    private MinioStorageService minioStorageService;

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

        int timeout = defaultTimeoutMs > 0 ? defaultTimeoutMs : 8000;

        // Cloud-Edge Mode: Gửi lệnh qua MQTT RPC -> Edge chụp frame và đẩy lên MinIO
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            return captureViaEdgeMqttRpc(camera, rtspUrl, timeout);
        }

        // Fallback Local HTTP Mode
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

    private CameraSnapshotResponse captureViaEdgeMqttRpc(Camera camera, String rtspUrl, int timeoutMs) {
        try {
            long rpcTimeout = Math.min(Math.max(timeoutMs, 3000L), 10000L);
            EdgeRpcReplyDto reply = edgeMqttControlService.sendRpcRequest(
                    "CAPTURE_SNAPSHOT",
                    camera.getCameraCode(),
                    Map.of("rtsp_url", rtspUrl, "timeout_ms", timeoutMs),
                    rpcTimeout
            );

            if (reply == null || !reply.isSuccess() || reply.getData() == null) {
                throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
            }

            Map<String, Object> data = reply.getData();
            String base64Data = (String) data.get("snapshot_base64");

            if ((base64Data == null || base64Data.isBlank()) && minioStorageService != null) {
                String bucket = (String) data.getOrDefault("bucket", "camera-snapshots");
                String objectKey = (String) (data.containsKey("object_key") ? data.get("object_key") : data.get("objectKey"));
                if (objectKey != null && !objectKey.isBlank()) {
                    try (InputStream is = minioStorageService.getObject(bucket, objectKey)) {
                        byte[] imgBytes = is.readAllBytes();
                        base64Data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imgBytes);
                    }
                }
            }

            if (base64Data == null || base64Data.isBlank()) {
                throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
            }

            Number widthNum = (Number) data.get("width");
            Number heightNum = (Number) data.get("height");

            return CameraSnapshotResponse.builder()
                    .success(true)
                    .snapshotBase64(base64Data)
                    .width(widthNum != null ? widthNum.intValue() : 854)
                    .height(heightNum != null ? heightNum.intValue() : 480)
                    .latencyMs(reply.getLatencyMs())
                    .capturedAt(OffsetDateTime.now())
                    .build();
        } catch (CameraException ce) {
            if (ce.getErrorCode() == CameraErrorCode.ERR_EDGE_001 || ce.getErrorCode() == CameraErrorCode.ERR_EDGE_002) {
                throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
            }
            throw ce;
        } catch (Exception e) {
            log.error("Lỗi trích xuất snapshot qua MQTT RPC cho camera [{}]: {}", camera.getCameraCode(), e.getMessage());
            throw new CameraException(CameraErrorCode.ERR_SNAPSHOT_001);
        }
    }
}
