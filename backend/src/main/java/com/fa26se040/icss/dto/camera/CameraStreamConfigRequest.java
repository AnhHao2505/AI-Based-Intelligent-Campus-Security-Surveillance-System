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

    @NotBlank(message = "Địa chỉ IP/Host không được để trống")
    @Size(max = 255, message = "Địa chỉ IP/Host không được vượt quá 255 ký tự")
    private String host;

    @NotNull(message = "Cổng kết nối RTSP không được để trống")
    @jakarta.validation.constraints.Min(value = 1, message = "Cổng kết nối phải lớn hơn hoặc bằng 1")
    @jakarta.validation.constraints.Max(value = 65535, message = "Cổng kết nối không được vượt quá 65535")
    private Integer port;

    @Size(max = 100, message = "Tên đăng nhập không được vượt quá 100 ký tự")
    private String username;

    @Size(max = 255, message = "Mã xác thực không được vượt quá 255 ký tự")
    private String credentialRef;

    @Size(max = 255, message = "Mật khẩu không được vượt quá 255 ký tự")
    private String password;

    @NotBlank(message = "Đường dẫn luồng chính không được để trống")
    @Size(max = 512, message = "Đường dẫn luồng chính không được vượt quá 512 ký tự")
    private String mainStreamPath;

    @Size(max = 512, message = "Đường dẫn luồng phụ không được vượt quá 512 ký tự")
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
