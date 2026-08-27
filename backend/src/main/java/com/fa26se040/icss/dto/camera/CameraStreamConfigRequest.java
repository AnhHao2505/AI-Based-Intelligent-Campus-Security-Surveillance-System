package com.fa26se040.icss.dto.camera;

import com.fa26se040.icss.entity.StreamProtocol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraStreamConfigRequest {

    @NotNull(message = "Protocol is required")
    private StreamProtocol protocol;

    @NotBlank(message = "Host is required")
    @Size(max = 255, message = "Host cannot exceed 255 characters")
    private String host;

    @NotNull(message = "Port is required")
    private Integer port;

    @Size(max = 100, message = "Username cannot exceed 100 characters")
    private String username;

    @Size(max = 255, message = "Credential reference cannot exceed 255 characters")
    private String credentialRef;

    @NotBlank(message = "Main stream URL is required")
    @Size(max = 512, message = "Main stream URL cannot exceed 512 characters")
    private String mainStreamUrl;

    @Size(max = 512, message = "Sub stream URL cannot exceed 512 characters")
    private String subStreamUrl;

    private Boolean streamEnabled;

    private Boolean reconnectEnabled;

    private Integer timeoutMs;
}
