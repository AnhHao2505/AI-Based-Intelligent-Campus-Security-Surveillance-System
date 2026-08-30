package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

import com.fa26se040.icss.entity.StreamProtocol;

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
