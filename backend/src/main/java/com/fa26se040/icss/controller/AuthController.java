package com.fa26se040.icss.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.fa26se040.icss.dto.AuthResponse;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.dto.GoogleLoginRequest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.dto.RegisterRequest;
import com.fa26se040.icss.dto.ResetPasswordRequest;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.service.AuthService;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-reset-link")
    public ResponseEntity<Void> sendResetLink(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Received request to send reset password link to: {}", request.getEmail());
        authService.sendResetLink(request);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Received request to reset password using token");
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/register")
    // @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserInfo> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Received request to register user with email: {} by Admin", request.getEmail());
        UserInfo response = authService.registerUser(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> localLogin(@Valid @RequestBody LoginRequest request) {
        log.info("Received local login request for email: {}", request.getEmail());
        AuthResponse response = authService.authenticateLocalUser(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {

        log.info("Received Google login request");
        AuthResponse response = authService.authenticateGoogleUser(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserInfo> getCurrentUser(@AuthenticationPrincipal String email) {
        UserInfo userInfo = authService.fetchCurrentUser(email);
        return ResponseEntity.ok(userInfo);
    }
}
