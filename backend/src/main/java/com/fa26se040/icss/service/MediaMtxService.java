package com.fa26se040.icss.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMtxService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${mediamtx.api.url:http://localhost:9997}")
    private String mediaMtxApiUrl;

    /**
     * Tự động đăng ký hoặc cập nhật luồng RTSP của Camera vào MediaMTX Gateway.
     * MediaMTX sẽ tự động tạo WebRTC WHEP endpoint: http://mediamtx:8889/{cameraCode}
     */
    public void syncCameraPath(String cameraCode, String rtspSourceUrl) {
        if (cameraCode == null || cameraCode.trim().isEmpty() || rtspSourceUrl == null || rtspSourceUrl.trim().isEmpty()) {
            return;
        }

        String pathName = formatPathName(cameraCode);
        String addUrl = mediaMtxApiUrl + "/v3/config/paths/add/" + pathName;
        String patchUrl = mediaMtxApiUrl + "/v3/config/paths/patch/" + pathName;

        Map<String, Object> payload = new HashMap<>();
        payload.put("source", rtspSourceUrl);
        payload.put("sourceProtocol", "tcp");
        payload.put("sourceOnDemand", true);
        payload.put("sourceOnDemandCloseAfter", "10s");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForObject(addUrl, request, Map.class);
            log.info("✅ [MediaMTX] Đã tạo thành công dynamic path cho camera [{}] -> {}", pathName, rtspSourceUrl);
        } catch (HttpClientErrorException e) {
            // Path đã tồn tại -> cập nhật bằng PATCH
            log.info("Path [{}] đã tồn tại trong MediaMTX, cập nhật nguồn mới...", pathName);
            try {
                restTemplate.patchForObject(patchUrl, request, Map.class);
                log.info("✅ [MediaMTX] Đã cập nhật thành công dynamic path cho camera [{}]", pathName);
            } catch (Exception patchErr) {
                log.warn("⚠️ [MediaMTX] Không thể cập nhật path [{}]: {}", pathName, patchErr.getMessage());
            }
        } catch (Exception e) {
            log.warn("⚠️ [MediaMTX] Không thể kết nối tới MediaMTX Control API ({}) để tạo path [{}]: {}",
                    mediaMtxApiUrl, pathName, e.getMessage());
        }
    }

    /**
     * Xóa path khỏi MediaMTX khi camera bị ngừng hoạt động (Decommissioned)
     */
    public void deleteCameraPath(String cameraCode) {
        if (cameraCode == null || cameraCode.trim().isEmpty()) {
            return;
        }

        String pathName = formatPathName(cameraCode);
        String deleteUrl = mediaMtxApiUrl + "/v3/config/paths/delete/" + pathName;

        try {
            restTemplate.delete(deleteUrl);
            log.info("✅ [MediaMTX] Đã xóa dynamic path cho camera [{}]", pathName);
        } catch (Exception e) {
            log.warn("⚠️ [MediaMTX] Không thể xóa path [{}]: {}", pathName, e.getMessage());
        }
    }

    public String formatPathName(String cameraCode) {
        if (cameraCode == null) return "";
        return cameraCode.toLowerCase().replaceAll("[^a-z0-9_-]", "").trim();
    }

    /**
     * Lấy trạng thái sẵn sàng (ready) của tất cả các path từ MediaMTX API.
     * key: pathName (lowercase), value: true nếu stream ready (đang đẩy frame), false nếu mất kết nối/chờ.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> getLivePathStatuses() {
        Map<String, Boolean> statusMap = new HashMap<>();
        try {
            String listUrl = mediaMtxApiUrl + "/v3/paths/list";
            Map<String, Object> resp = restTemplate.getForObject(listUrl, Map.class);
            if (resp != null && resp.containsKey("items")) {
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
        } catch (Exception e) {
            log.debug("MediaMTX paths list check failed: {}", e.getMessage());
        }
        return statusMap;
    }
}
