package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.AuthResponse;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.dto.GoogleLoginRequest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.dto.ResetPasswordRequest;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-reset-link")
    public ResponseEntity<ApiResponse<Void>> sendResetLink(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Received request to send reset password link to: {}", request.getEmail());
        authService.sendResetLink(request);
        return ResponseEntity.ok(ApiResponse.success("Đã gửi liên kết đặt lại mật khẩu đến email"));
    }

    @PutMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Received request to reset password using token");
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Đặt lại mật khẩu thành công"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> localLogin(@Valid @RequestBody LoginRequest request) {
        log.info("Received local login request for email: {}", request.getEmail());
        AuthResponse response = authService.authenticateLocalUser(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Đăng nhập thành công"));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        log.info("Received Google login request");
        AuthResponse response = authService.authenticateGoogleUser(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Đăng nhập bằng Google thành công"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserInfo>> getCurrentUser(@AuthenticationPrincipal String email) {
        UserInfo userInfo = authService.fetchCurrentUser(email);
        return ResponseEntity.ok(ApiResponse.success(userInfo, "Lấy thông tin người dùng hiện tại thành công"));
    }
}
