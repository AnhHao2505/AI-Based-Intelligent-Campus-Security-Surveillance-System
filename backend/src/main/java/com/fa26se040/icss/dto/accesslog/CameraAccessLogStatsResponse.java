package com.fa26se040.icss.dto.accesslog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraAccessLogStatsResponse {
    private String cameraCode;
    private long totalToday;
    private long authorizedToday;
    private long unauthorizedToday;
    private long outOfTimeToday;
    private long unknownToday;
    private long intrusionToday;
}
