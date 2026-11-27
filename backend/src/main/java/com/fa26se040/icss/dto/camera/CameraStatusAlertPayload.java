package com.fa26se040.icss.dto.camera;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload STOMP cho cảnh báo trạng thái kết nối camera (Video Loss / Recovery):
 * - Web broadcast: /topic/cameras/status-alerts
 * - Mobile kênh riêng từng bảo vệ đang trực: /topic/guards/{guardId}/camera-alerts
 */
public record CameraStatusAlertPayload(
        String event,
        UUID cameraId,
        String cameraCode,
        String cameraName,
        String areaName,
        String building,
        String floor,
        OffsetDateTime offlineSince,
        OffsetDateTime timestamp
) {
}
