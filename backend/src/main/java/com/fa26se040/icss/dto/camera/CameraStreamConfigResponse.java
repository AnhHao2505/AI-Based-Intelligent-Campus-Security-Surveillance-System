package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraStreamConfigResponse {
    private UUID id;
    private String host;
    private Integer port;
    private String username;
    private String credentialRef;
    private String mainStreamPath;
    private String subStreamPath;
    private Integer retryTimeBeforeAlerting;
    private Integer timeoutMs;
}
