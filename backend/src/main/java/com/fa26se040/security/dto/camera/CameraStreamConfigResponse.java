package com.fa26se040.security.dto.camera;

import com.fa26se040.security.entity.StreamProtocol;
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
    private StreamProtocol protocol;
    private String host;
    private Integer port;
    private String username;
    private String credentialRef;
    private String mainStreamUrl;
    private String subStreamUrl;
    private Boolean streamEnabled;
    private Boolean reconnectEnabled;
    private Integer timeoutMs;
}
