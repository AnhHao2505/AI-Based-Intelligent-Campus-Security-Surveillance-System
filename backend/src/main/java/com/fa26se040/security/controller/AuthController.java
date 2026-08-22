package com.fa26se040.security.controller;

import com.fa26se040.security.dto.AuthResponse;
import com.fa26se040.security.dto.GoogleLoginRequest;
import com.fa26se040.security.dto.UserInfo;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.exception.UnauthorizedException;
import com.fa26se040.security.repository.UserRepository;
import com.fa26se040.security.service.AuthService;
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
