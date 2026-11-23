package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.edge.EdgeHeartbeatDto;
import com.fa26se040.icss.repository.CameraRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tự tắt "camera ma" trên edge: camera AI báo đang chạy (heartbeat campus/edge/status/{edge_id}) nhưng DB không còn
 * (bị xoá bằng migration / SQL, ví dụ V134_1 dọn camera chưa có toạ độ). Xoá theo đường đó không gửi lệnh tắt qua MQTT,
 * còn cấu hình stream retained (campus/edge/config/cameras/{code}, enabled=true) vẫn nằm trên broker, nên AI tiếp tục
 * phân tích camera và hỏi check-entry -> "Camera không tồn tại" cho mọi người nó nhìn thấy, kể cả sau khi khởi động lại.
 *
 * Gửi lại cấu hình enabled=false (retained) cho camera đó: AI dừng worker ngay, và bản retained mới thay bản cũ nên
 * AI khởi động lại cũng không chạy lại camera.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeGhostCameraReconciler {

    /** Không gửi lặp lệnh tắt cùng một camera trong khoảng này (heartbeat tới mỗi vài giây khi trạng thái đổi). */
    static final long RESEND_INTERVAL_MS = 60_000L;

    private final CameraRepository cameraRepository;
    private final EdgeMqttControlService edgeMqttControlService;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> lastDisabledAt = new ConcurrentHashMap<>();

    public void reconcile(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            EdgeHeartbeatDto dto = objectMapper.readValue(payload, EdgeHeartbeatDto.class);
            if (dto.getStatus() != null && !"ONLINE".equalsIgnoreCase(dto.getStatus().trim())) {
                return;
            }
            Set<String> reported = reportedCameraCodes(dto.getCameras());
            if (reported.isEmpty()) {
                return;
            }

            Set<String> existing = new HashSet<>(cameraRepository.findExistingUpperCameraCodes(reported));
            long now = System.currentTimeMillis();
            for (String code : reported) {
                if (existing.contains(code)) {
                    lastDisabledAt.remove(code);
                    continue;
                }
                Long last = lastDisabledAt.get(code);
                if (last != null && now - last < RESEND_INTERVAL_MS) {
                    continue;
                }
                edgeMqttControlService.publishCameraStreamConfig(code, "", false, null);
                lastDisabledAt.put(code, now);
                log.warn("👻 [Edge Reconcile] Edge [{}] đang chạy camera [{}] không còn trong DB -> gửi enabled=false (retained) để tắt",
                        dto.getEdgeId(), code);
            }
        } catch (Exception e) {
            log.warn("⚠️ [Edge Reconcile] Không đối chiếu được danh sách camera trong heartbeat: {}", e.getMessage());
        }
    }

    private static Set<String> reportedCameraCodes(List<Map<String, Object>> cameras) {
        Set<String> codes = new HashSet<>();
        if (cameras == null) {
            return codes;
        }
        for (Map<String, Object> item : cameras) {
            Object codeObj = item != null ? item.get("camera_code") : null;
            if (codeObj == null) {
                continue;
            }
            String code = String.valueOf(codeObj).trim().toUpperCase(Locale.ROOT);
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }
}
