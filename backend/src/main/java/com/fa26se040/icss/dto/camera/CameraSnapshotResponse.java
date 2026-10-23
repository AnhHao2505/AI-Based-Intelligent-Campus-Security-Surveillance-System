package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * DTO trả về khung hình tĩnh (snapshot) từ camera.
 * Dùng cho việc cấu hình vẽ ROI hoặc kiểm tra sai lệch góc quan sát (Drift Check).
 * Thao tác này là Pure Read-Only, không làm thay đổi trạng thái camera hay ghi health log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraSnapshotResponse {
    private boolean success;
    private String snapshotBase64;
    private Integer width;
    private Integer height;
    private Long latencyMs;
    private OffsetDateTime capturedAt;
    private String errorMessage;
}
