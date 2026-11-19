package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
                // MediaMTX API là http:// thường, không dùng h2c: tránh header Upgrade: h2c của HTTP/2 mặc định
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }

    public static final String INTERNAL_RTSP_USER = "icss-internal";

    @Value("${mediamtx.api.url:http://localhost:9997}")
    private String mediaMtxApiUrl = "http://localhost:9997";

    @Value("${mediamtx.rtsp.url:rtsp://localhost:8554}")
    private String mediaMtxRtspUrl = "rtsp://localhost:8554";

    @Value("${mediamtx.whep.base-url:http://localhost:8889}")
    private String mediaMtxWhepBaseUrl = "http://localhost:8889";

    @Value("${mediamtx.playback.url:http://localhost:9996}")
    private String mediaMtxPlaybackUrl = "http://localhost:9996";

    @Value("${icss.internal.service-key:}")
    private String internalServiceKey = "";

    /**
     * Lấy địa chỉ RTSP phân phối nội bộ từ MediaMTX Gateway (ví dụ: rtsp://icss-internal:<hmac>@localhost:8554/cam-001).
     */
    public String getGatewayRtspUrl(String cameraCode) {
        String base = (mediaMtxRtspUrl != null && !mediaMtxRtspUrl.isBlank()) ? mediaMtxRtspUrl : "rtsp://localhost:8554";
        String cleanBase = base.replaceAll("/+$", "");
        String pathName = formatPathName(cameraCode);
        if (internalServiceKey != null && !internalServiceKey.isBlank()
                && cleanBase.startsWith("rtsp://") && !cleanBase.contains("@")) {
            String secret = AnnotatedStreamTokenService.generateInternalRtspSecret(cameraCode, internalServiceKey);
            cleanBase = "rtsp://" + INTERNAL_RTSP_USER + ":" + secret + "@" + cleanBase.substring("rtsp://".length());
        }
        return cleanBase + "/" + pathName;
    }

    /**
     * Che thông tin user:password/HMAC trong URL RTSP trước khi ghi log.
     */
    public static String maskRtspCredentials(String rtspUrl) {
        if (rtspUrl == null || rtspUrl.isBlank()) {
            return "";
        }
        return RTSP_CREDENTIALS_PATTERN.matcher(rtspUrl).replaceAll("$1***$3");
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

        HttpHeaders headers = createControlApiHeaders();
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForObject(addUrl, request, Map.class);
            log.info("✅ [MediaMTX] Đã tạo thành công dynamic path cho camera [{}] -> {}", pathName, maskRtspCredentials(rtspSourceUrl));
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

    private HttpHeaders createControlApiHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalServiceKey != null && !internalServiceKey.isBlank()) {
            String apiSecret = AnnotatedStreamTokenService.generateInternalApiSecret(internalServiceKey);
            String basic = java.util.Base64.getEncoder().encodeToString(
                    (INTERNAL_RTSP_USER + ":" + apiSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        return headers;
    }

    private void deleteSinglePath(String pathName) {
        String deleteUrl = mediaMtxApiUrl + "/v3/config/paths/delete/" + pathName;
        try {
            restTemplate.exchange(deleteUrl, HttpMethod.DELETE, new HttpEntity<>(createControlApiHeaders()), Void.class);
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
            Map<String, Object> resp = restTemplate.exchange(
                    listUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(createControlApiHeaders()),
                    Map.class
            ).getBody();
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

    public record DvrSegmentDto(
            String path,
            String start,
            long startMs,
            String end,
            long endMs,
            double durationSeconds
    ) {}

    public record DvrTimelineDto(
            String cameraCode,
            String streamPath,
            long serverNowMs,
            List<DvrSegmentDto> segments
    ) {}

    /**
     * Lấy danh sách các khoảng thời gian DVR liên tục từ MediaMTX Playback Server (:9996/list).
     * Tự động kiểm tra path chính (cam-xxx) và fallback sang luồng phân giải cao (cam-xxx_main).
     */
    public DvrTimelineDto getDvrTimeline(String cameraCode, Long startMsFilter, Long endMsFilter) {
        long nowMs = System.currentTimeMillis();
        String basePath = formatPathName(cameraCode);
        if (basePath.isEmpty() || mediaMtxPlaybackUrl == null || mediaMtxPlaybackUrl.isBlank()) {
            return new DvrTimelineDto(cameraCode, basePath, nowMs, Collections.emptyList());
        }

        List<DvrSegmentDto> segments = fetchDvrSegmentsForPath(basePath, startMsFilter, endMsFilter);
        String resolvedPath = basePath;
        if (segments.isEmpty() && !basePath.endsWith("_main")) {
            String mainPath = basePath + "_main";
            List<DvrSegmentDto> mainSegments = fetchDvrSegmentsForPath(mainPath, startMsFilter, endMsFilter);
            if (!mainSegments.isEmpty()) {
                segments = mainSegments;
                resolvedPath = mainPath;
            }
        }
        return new DvrTimelineDto(cameraCode != null ? cameraCode.trim().toUpperCase() : resolvedPath, resolvedPath, nowMs, segments);
    }

    @SuppressWarnings("unchecked")
    private List<DvrSegmentDto> fetchDvrSegmentsForPath(String pathName, Long startMsFilter, Long endMsFilter) {
        String basePlayback = mediaMtxPlaybackUrl.trim().replaceAll("/+$", "");
        StringBuilder url = new StringBuilder(basePlayback)
                .append("/list?path=")
                .append(URLEncoder.encode(pathName, StandardCharsets.UTF_8));
        if (startMsFilter != null && startMsFilter > 0) {
            url.append("&start=").append(URLEncoder.encode(Instant.ofEpochMilli(startMsFilter).toString(), StandardCharsets.UTF_8));
        }
        if (endMsFilter != null && endMsFilter > 0) {
            url.append("&end=").append(URLEncoder.encode(Instant.ofEpochMilli(endMsFilter).toString(), StandardCharsets.UTF_8));
        }

        try {
            List<?> rawList = restTemplate.exchange(
                    url.toString(),
                    HttpMethod.GET,
                    new HttpEntity<>(createControlApiHeaders()),
                    List.class
            ).getBody();
            if (rawList == null || rawList.isEmpty()) {
                return Collections.emptyList();
            }
            List<DvrSegmentDto> result = new ArrayList<>();
            for (Object itemObj : rawList) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                Object startObj = item.get("start");
                Object durObj = item.get("duration");
                if (startObj == null || !(durObj instanceof Number durNum)) {
                    continue;
                }
                double durationSec = durNum.doubleValue();
                if (durationSec <= 0) {
                    continue;
                }
                long startMs = OffsetDateTime.parse(String.valueOf(startObj)).toInstant().toEpochMilli();
                long endMs = startMs + Math.round(durationSec * 1000.0);
                String endIso = Instant.ofEpochMilli(endMs).toString();
                result.add(new DvrSegmentDto(pathName, String.valueOf(startObj), startMs, endIso, endMs, durationSec));
            }
            result.sort(Comparator.comparingLong(DvrSegmentDto::startMs));
            return result;
        } catch (HttpClientErrorException e) {
            // MediaMTX trả 400/404 khi thư mục recording chưa tồn tại cho path này
            log.debug("MediaMTX DVR chưa có bản ghi cho path [{}]: {}", pathName, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Lỗi truy vấn danh sách DVR từ MediaMTX cho path [{}]: {}", pathName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Stream luồng fMP4 trực tiếp từ MediaMTX Playback Server (:9996/get) bắt đầu tại mốc startMs.
     * Hỗ trợ playback tức thì bất kỳ thời điểm nào trên dòng thời gian YouTube-Live DVR.
     */
    public ResponseEntity<StreamingResponseBody> streamDvrPlayback(
            String cameraCode,
            long requestedStartMs,
            Double requestedDurationSec,
            String explicitPath) {
        if (mediaMtxPlaybackUrl == null || mediaMtxPlaybackUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        String basePath = formatPathName(cameraCode);
        String candidatePath = (explicitPath != null && !explicitPath.isBlank())
                ? formatPathName(explicitPath)
                : null;
        if (candidatePath != null && !candidatePath.equals(basePath) && !candidatePath.equals(basePath + "_main")) {
            candidatePath = basePath;
        }

        DvrTimelineDto timeline = getDvrTimeline(cameraCode, null, null);
        List<DvrSegmentDto> segments = timeline.segments();
        if (segments.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String resolvedPath = (candidatePath != null && !candidatePath.isEmpty()) ? candidatePath : timeline.streamPath();
        DvrSegmentDto matchedSeg = null;
        for (DvrSegmentDto seg : segments) {
            if (requestedStartMs >= seg.startMs() - 5000L && requestedStartMs <= seg.endMs() + 5000L) {
                matchedSeg = seg;
                break;
            }
        }
        // Nếu không khớp chính xác (ví dụ gap nhỏ giữa 2 đoạn), chọn đoạn gần nhất sau requestedStartMs nếu trong vòng 60s
        if (matchedSeg == null) {
            for (DvrSegmentDto seg : segments) {
                if (seg.startMs() >= requestedStartMs && seg.startMs() - requestedStartMs <= 60_000L) {
                    matchedSeg = seg;
                    break;
                }
            }
        }
        if (matchedSeg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long effectiveStartMs = Math.max(matchedSeg.startMs(), requestedStartMs);
        // Bảo đảm có tối thiểu ~2 giây dữ liệu ở sát mép LIVE để thẻ <video> giải mã được khung hình đầu
        if (matchedSeg.endMs() - effectiveStartMs < 2000L && matchedSeg.endMs() - matchedSeg.startMs() >= 2000L) {
            effectiveStartMs = matchedSeg.endMs() - 2000L;
        }
        double availableSec = Math.max(1.0, (matchedSeg.endMs() - effectiveStartMs) / 1000.0);
        double maxSliceSec = (requestedDurationSec != null && requestedDurationSec > 0)
                ? Math.min(requestedDurationSec, 3600.0)
                : 1800.0;
        double effectiveDurationSec = Math.max(1.0, Math.min(availableSec, maxSliceSec));

        String startParam = Instant.ofEpochMilli(effectiveStartMs).toString();
        String basePlayback = mediaMtxPlaybackUrl.trim().replaceAll("/+$", "");
        String targetUrl = String.format(Locale.US,
                "%s/get?path=%s&start=%s&duration=%.3f&format=fmp4",
                basePlayback,
                URLEncoder.encode(resolvedPath, StandardCharsets.UTF_8),
                URLEncoder.encode(startParam, StandardCharsets.UTF_8),
                effectiveDurationSec);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            String authHeader = createControlApiHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && !authHeader.isBlank()) {
                conn.setRequestProperty(HttpHeaders.AUTHORIZATION, authHeader);
            }
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(30000);
            int status = conn.getResponseCode();
            if (status >= 400) {
                try (InputStream err = conn.getErrorStream()) {
                    String errMsg = err != null ? new String(err.readAllBytes(), StandardCharsets.UTF_8) : "";
                    log.warn("MediaMTX playback /get trả HTTP {} cho [{}]: {}", status, resolvedPath, errMsg);
                }
                conn.disconnect();
                return ResponseEntity.status(status == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY).build();
            }

            StreamingResponseBody body = outputStream -> {
                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                } finally {
                    conn.disconnect();
                }
            };

            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("video/mp4"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-DVR-Start-Ms", String.valueOf(effectiveStartMs))
                    .header("X-DVR-Duration-Sec", String.format(Locale.US, "%.3f", effectiveDurationSec))
                    .body(body);
        } catch (Exception e) {
            log.error("Lỗi proxy luồng DVR từ MediaMTX cho [{}]: {}", resolvedPath, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
