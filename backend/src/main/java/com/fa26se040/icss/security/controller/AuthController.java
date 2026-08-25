package com.fa26se040.icss.security.controller;

import com.fa26se040.icss.security.dto.AuthResponse;
import com.fa26se040.icss.security.dto.GoogleLoginRequest;
import com.fa26se040.icss.security.dto.UserInfo;
import com.fa26se040.icss.security.exception.UnauthorizedException;
import com.fa26se040.icss.security.security.CurrentUser;
import com.fa26se040.icss.security.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(
            @Valid @RequestBody GoogleLoginRequest request,
            HttpServletRequest httpServletRequest) {
        log.info("Received Google login request");
        AuthResponse response = authService.authenticateGoogleUser(request, httpServletRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserInfo> getCurrentUser(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            throw new UnauthorizedException("Phiên đăng nhập không hợp lệ hoặc thiếu token.");
        }
        UserInfo userInfo = authService.fetchCurrentUser(currentUser.id());
        return ResponseEntity.ok(userInfo);
    }
}
