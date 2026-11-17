package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMtxService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern RTSP_CREDENTIALS_PATTERN = Pattern.compile("(rtsps?://[^:/@\\s]+:)([^@\\s]+)(@)", Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }

    static String maskRtspUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return RTSP_CREDENTIALS_PATTERN.matcher(url).replaceAll("$1***$3");
    }

    @Value("${mediamtx.api.url:http://localhost:9997}")
    private String mediaMtxApiUrl = "http://localhost:9997";

    @Value("${mediamtx.rtsp.url:rtsp://localhost:8554}")
    private String mediaMtxRtspUrl = "rtsp://localhost:8554";

    @Value("${mediamtx.whep.base-url:http://localhost:8889}")
    private String mediaMtxWhepBaseUrl = "http://localhost:8889";

    /**
     * Lấy địa chỉ RTSP phân phối nội bộ từ MediaMTX Gateway (ví dụ: rtsp://localhost:8554/cam-001).
     */
    public String getGatewayRtspUrl(String cameraCode) {
        String base = (mediaMtxRtspUrl != null && !mediaMtxRtspUrl.isBlank()) ? mediaMtxRtspUrl : "rtsp://localhost:8554";
        return base.replaceAll("/+$", "") + "/" + formatPathName(cameraCode);
    }

    /**
     * Lấy địa chỉ WHEP WebRTC theo cấu hình môi trường (hỗ trợ cả Local HTTP và Cloud HTTPS Relay).
     */
    public String getWhepUrl(String cameraCode) {
        String pathName = formatPathName(cameraCode);
        if (pathName.isEmpty()) {
            return null;
        }
        String base = (mediaMtxWhepBaseUrl != null && !mediaMtxWhepBaseUrl.isBlank())
                ? mediaMtxWhepBaseUrl.trim().replaceAll("/+$", "")
                : "http://localhost:8889";
        return base + "/" + pathName + "/whep";
    }

    /**
     * Tự động đăng ký hoặc cập nhật luồng RTSP của Camera vào MediaMTX Gateway (best-effort).
     */
    public void syncCameraPath(String cameraCode, String rtspSourceUrl) {
        doSyncCameraPath(cameraCode, rtspSourceUrl, false);
    }

    /**
     * Đồng bộ luồng RTSP sang MediaMTX Gateway tuân theo nguyên tắc Gateway-First.
     * Ném lỗi CameraException(ERR_STREAM_002) nếu không thể kết nối hoặc MediaMTX từ chối.
     */
    public void syncCameraPathStrict(String cameraCode, String rtspSourceUrl) {
        doSyncCameraPath(cameraCode, rtspSourceUrl, true);
    }

    private void doSyncCameraPath(String cameraCode, String rtspSourceUrl, boolean strict) {
        if (cameraCode == null || cameraCode.trim().isEmpty() || rtspSourceUrl == null || rtspSourceUrl.trim().isEmpty()) {
            return;
        }
        if (mediaMtxApiUrl == null || mediaMtxApiUrl.isBlank()) {
            log.debug("ℹ️ [MediaMTX] mediamtx.api.url trống (Cloud mode), bỏ qua sync trực tiếp cho [{}]", cameraCode);
            return;
        }

        String pathName = formatPathName(cameraCode);
        String addUrl = mediaMtxApiUrl + "/v3/config/paths/add/" + pathName;
        String patchUrl = mediaMtxApiUrl + "/v3/config/paths/patch/" + pathName;

        Map<String, Object> payload = new HashMap<>();
        payload.put("source", rtspSourceUrl);
        payload.put("sourceProtocol", "tcp");
        payload.put("sourceOnDemand", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForObject(addUrl, request, Map.class);
            log.info("✅ [MediaMTX] Đã tạo thành công dynamic path cho camera [{}] -> {}", pathName, maskRtspUrl(rtspSourceUrl));
        } catch (HttpClientErrorException e) {
            log.info("Path [{}] đã tồn tại trong MediaMTX, cập nhật nguồn mới...", pathName);
            try {
                restTemplate.patchForObject(patchUrl, request, Map.class);
                log.info("✅ [MediaMTX] Đã cập nhật thành công dynamic path cho camera [{}]", pathName);
            } catch (Exception patchErr) {
                if (!strict) {
                    log.warn("⚠️ [MediaMTX] Không thể cập nhật path [{}]: {}", pathName, patchErr.getMessage());
                    return;
                }
                log.warn("⚠️ [MediaMTX] PATCH thất bại ({}), thử xóa và tạo lại path [{}]...", patchErr.getMessage(), pathName);
                try {
                    deleteCameraPath(cameraCode);
                    restTemplate.postForObject(addUrl, request, Map.class);
                    log.info("✅ [MediaMTX] Đã tạo lại thành công path cho camera [{}]", pathName);
                } catch (Exception recreateErr) {
                    log.error("❌ [MediaMTX] Không thể cập nhật hoặc tái tạo path [{}]: {}", pathName, recreateErr.getMessage());
                    throw new com.fa26se040.icss.exception.CameraException(com.fa26se040.icss.exception.CameraErrorCode.ERR_STREAM_002);
                }
            }
        } catch (Exception e) {
            if (!strict) {
                log.warn("⚠️ [MediaMTX] Không thể kết nối tới MediaMTX Control API ({}) để tạo path [{}]: {}",
                        mediaMtxApiUrl, pathName, e.getMessage());
                return;
            }
            log.error("❌ [MediaMTX] Lỗi kết nối tới MediaMTX Control API ({}) để tạo path [{}]: {}",
                    mediaMtxApiUrl, pathName, e.getMessage());
            throw new com.fa26se040.icss.exception.CameraException(com.fa26se040.icss.exception.CameraErrorCode.ERR_STREAM_002);
        }
    }

    /**
     * Xóa path khỏi MediaMTX khi camera bị ngừng hoạt động (Decommissioned)
     */
    public void deleteCameraPath(String cameraCode) {
        if (cameraCode == null || cameraCode.trim().isEmpty() || mediaMtxApiUrl == null || mediaMtxApiUrl.isBlank()) {
            return;
        }

        String pathName = formatPathName(cameraCode);
        deleteSinglePath(pathName);
        if (!pathName.endsWith("_main")) {
            deleteSinglePath(pathName + "_main");
        }
    }

    private void deleteSinglePath(String pathName) {
        String deleteUrl = mediaMtxApiUrl + "/v3/config/paths/delete/" + pathName;
        try {
            restTemplate.delete(deleteUrl);
            log.info("✅ [MediaMTX] Đã xóa dynamic path cho camera [{}]", pathName);
        } catch (Exception e) {
            log.debug("⚠️ [MediaMTX] Không thể xóa path [{}]: {}", pathName, e.getMessage());
        }
    }

    public String formatPathName(String cameraCode) {
        if (cameraCode == null) return "";
        return cameraCode.toLowerCase().replaceAll("[^a-z0-9_-]", "").trim();
    }

    public enum PathReadinessState {
        READY,
        NOT_READY,
        UNAVAILABLE
    }

    @SuppressWarnings("unchecked")
    public Map<String, Boolean> getLivePathStatuses() {
        if (mediaMtxApiUrl == null || mediaMtxApiUrl.isBlank()) {
            return null;
        }
        Map<String, Boolean> statusMap = new HashMap<>();
        try {
            String listUrl = mediaMtxApiUrl + "/v3/paths/list";
            Map<String, Object> resp = restTemplate.getForObject(listUrl, Map.class);
            if (resp == null) {
                return null;
            }
            if (resp.containsKey("items")) {
                Object itemsObj = resp.get("items");
                if (itemsObj instanceof java.util.List<?> itemsList) {
                    for (Object itemObj : itemsList) {
                        if (itemObj instanceof Map<?, ?> item) {
                            String name = (String) item.get("name");
                            Boolean ready = (Boolean) item.get("ready");
                            if (name != null) {
                                statusMap.put(name.toLowerCase().trim(), Boolean.TRUE.equals(ready));
                            }
                        }
                    }
                }
            }
            return statusMap;
        } catch (Exception e) {
            log.debug("MediaMTX paths list check failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Chờ tối đa timeoutMs để MediaMTX kéo thành công track video từ nguồn RTSP (ready=true).
     */
    public PathReadinessState awaitPathReady(String cameraCode, long timeoutMs) {
        if (cameraCode == null || cameraCode.trim().isEmpty()
                || mediaMtxApiUrl == null || mediaMtxApiUrl.isBlank()) {
            return PathReadinessState.UNAVAILABLE;
        }
        String pathName = formatPathName(cameraCode);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);

        while (true) {
            Map<String, Boolean> statuses = getLivePathStatuses();
            if (statuses == null) {
                return PathReadinessState.UNAVAILABLE;
            }
            if (Boolean.TRUE.equals(statuses.get(pathName))
                    || Boolean.TRUE.equals(statuses.get(pathName + "_main"))) {
                return PathReadinessState.READY;
            }
            if (System.currentTimeMillis() >= deadline) {
                return PathReadinessState.NOT_READY;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return PathReadinessState.NOT_READY;
            }
        }
    }
}
