package com.fa26se040.icss.dto.camera;

import com.fa26se040.icss.enums.OperationalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO phản hồi kết quả kích hoạt (Start) hoặc ngắt (Stop) luồng giám sát của camera.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraStreamLifecycleResponse {
    private boolean success;
    private UUID cameraId;
    private String cameraCode;
    private OperationalStatus operationalStatus;
    private String message;
    private OffsetDateTime timestamp;
}
