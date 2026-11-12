package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.edge.EdgeHeartbeatDto;
import com.fa26se040.icss.entity.EdgeDeviceStatus;
import com.fa26se040.icss.repository.EdgeDeviceStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeStatusService {

    private final EdgeDeviceStatusRepository edgeDeviceStatusRepository;
    private final ObjectMapper objectMapper;

    /**
     * Xử lý tin nhắn heartbeat / status từ thiết bị biên qua MQTT topic campus/edge/status/{edge_id}
     */
    @Transactional
    public void processHeartbeat(String topic, String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("⚠️ [EdgeStatusService] Payload trạng thái rỗng, bỏ qua");
            return;
        }

        try {
            EdgeHeartbeatDto dto = objectMapper.readValue(payload, EdgeHeartbeatDto.class);
            String edgeId = dto.getEdgeId();
            if (edgeId == null || edgeId.isBlank()) {
                // Trích xuất từ topic nếu payload không có
                if (topic != null && topic.startsWith("campus/edge/status/")) {
                    edgeId = topic.substring("campus/edge/status/".length()).trim();
                }
            }
            if (edgeId == null || edgeId.isBlank()) {
                log.warn("⚠️ [EdgeStatusService] Không xác định được edge_id từ topic [{}] và payload", topic);
                return;
            }

            // last_seen dùng GIỜ SERVER lúc nhận tin: checkOfflineEdges() so với giờ server, nếu lấy ts của edge
            // thì edge lệch giờ > 90s (Raspberry Pi không RTC / chưa NTP) sẽ bị đánh OFFLINE liên tục dù vẫn gửi đều.
            // Tin LWT OFFLINE cũng mang ts lúc connect (có thể cũ nhiều giờ) nên không dùng được làm mốc.
            OffsetDateTime eventTime = OffsetDateTime.now();
            if (dto.getTs() != null && !dto.getTs().isBlank()) {
                try {
                    OffsetDateTime edgeTs = OffsetDateTime.parse(dto.getTs().trim());
                    long skewSeconds = Math.abs(java.time.Duration.between(edgeTs, eventTime).getSeconds());
                    if (skewSeconds > 60 && "ONLINE".equalsIgnoreCase(dto.getStatus())) {
                        log.warn("⚠️ [EdgeStatusService] Đồng hồ edge [{}] lệch {}s so với server (ts={}), kiểm tra NTP trên thiết bị",
                                edgeId, skewSeconds, dto.getTs());
                    }
                } catch (DateTimeParseException ex) {
                    log.warn("⚠️ [EdgeStatusService] Timestamp ts [{}] sai định dạng", dto.getTs());
                }
            }

            String status = dto.getStatus() != null ? dto.getStatus().toUpperCase() : "ONLINE";
            String camerasJson = null;
            if (dto.getCameras() != null) {
                try {
                    camerasJson = objectMapper.writeValueAsString(dto.getCameras());
                } catch (Exception e) {
                    camerasJson = "[]";
                }
            }

            EdgeDeviceStatus deviceStatus = edgeDeviceStatusRepository.findById(edgeId)
                    .orElse(EdgeDeviceStatus.builder()
                            .edgeId(edgeId)
                            .createdAt(eventTime)
                            .build());

            deviceStatus.setStatus(status);
            deviceStatus.setLastSeen(eventTime);
            if (camerasJson != null) {
                deviceStatus.setCamerasJson(camerasJson);
            }
            if (dto.getGpu() != null) {
                deviceStatus.setGpu(dto.getGpu());
            }
            if (dto.getVersion() != null) {
                deviceStatus.setVersion(dto.getVersion());
            }
            deviceStatus.setUpdatedAt(OffsetDateTime.now());

            edgeDeviceStatusRepository.save(deviceStatus);
            log.debug("💓 [EdgeStatusService] Đã cập nhật trạng thái edge [{}] -> {} (last seen: {})",
                    edgeId, status, eventTime);
        } catch (Exception e) {
            log.error("❌ [EdgeStatusService] Lỗi khi xử lý heartbeat edge từ topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Scheduled job: Kiểm tra các thiết bị biên quá 3 chu kỳ heartbeat (90 giây) không gửi tin
     * để tự động đánh dấu OFFLINE và ghi log WARN.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void checkOfflineEdges() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(90);
        List<EdgeDeviceStatus> offlineEdges = edgeDeviceStatusRepository.findByStatusAndLastSeenBefore("ONLINE", cutoff);
        for (EdgeDeviceStatus edge : offlineEdges) {
            edge.setStatus("OFFLINE");
            edge.setUpdatedAt(OffsetDateTime.now());
            edgeDeviceStatusRepository.save(edge);
            log.warn("⚠️ [Edge Heartbeat] Thiết bị biên [{}] quá 3 chu kỳ (90s) không có heartbeat (last seen: {}), đánh dấu OFFLINE",
                    edge.getEdgeId(), edge.getLastSeen());
        }
    }
}
