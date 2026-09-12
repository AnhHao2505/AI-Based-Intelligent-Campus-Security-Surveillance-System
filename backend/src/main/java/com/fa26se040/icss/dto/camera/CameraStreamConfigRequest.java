package com.fa26se040.icss.dto.camera;

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

    @NotBlank(message = "Host is required")
    @Size(max = 255, message = "Host cannot exceed 255 characters")
    private String host;

    @NotNull(message = "Port is required")
    @jakarta.validation.constraints.Min(value = 1, message = "Port must be at least 1")
    @jakarta.validation.constraints.Max(value = 65535, message = "Port cannot exceed 65535")
    private Integer port;

    @Size(max = 100, message = "Username cannot exceed 100 characters")
    private String username;

    @Size(max = 255, message = "Credential reference cannot exceed 255 characters")
    private String credentialRef;

    @Size(max = 255, message = "Password cannot exceed 255 characters")
    private String password;

    @NotBlank(message = "Main stream path is required")
    @Size(max = 512, message = "Main stream path cannot exceed 512 characters")
    private String mainStreamPath;

    @Size(max = 512, message = "Sub stream path cannot exceed 512 characters")
    private String subStreamPath;

    private Integer retryTimeBeforeAlerting;

    private Integer timeoutMs;

    public String getEffectivePassword() {
        if (password != null && !password.trim().isEmpty()) {
            return password.trim();
        }
        if (credentialRef != null && !credentialRef.trim().isEmpty()) {
            return credentialRef.trim();
        }
        return null;
    }
}
