package com.fa26se040.security.service;

import com.fa26se040.security.dto.AuthResponse;
import com.fa26se040.security.dto.GoogleLoginRequest;
import com.fa26se040.security.dto.LoginRequest;
import com.fa26se040.security.dto.RegisterRequest;
import com.fa26se040.security.dto.UserInfo;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.exception.UnauthorizedException;
import com.fa26se040.security.repository.UserRepository;
import com.fa26se040.security.security.GoogleTokenVerifier;
import com.fa26se040.security.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    public UserInfo registerUser(RegisterRequest request) {
        log.info("Registering new user with email: {} and role: {}", request.getEmail(), request.getRole());

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email is already in use");
        }

        if (userRepository.findByUserCode(request.getUserCode()).isPresent()) {
            throw new IllegalArgumentException("User code is already in use");
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .userCode(request.getUserCode())
                .role(request.getRole())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        return UserInfo.builder()
                .id(savedUser.getId())
                .fullName(savedUser.getFullName())
                .email(savedUser.getEmail())
                .role(savedUser.getRole().name())
                .userCode(savedUser.getUserCode())
                .build();
    }

    public AuthResponse authenticateLocalUser(LoginRequest request) {
        log.info("Authenticating local user with email: {}", request.getEmail());
        User user = userRepository.findByEmailAndIsActiveTrue(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Authentication failed: User email not found or inactive: {}", request.getEmail());
                    return new UnauthorizedException("Invalid email or password");
                });

        if (user.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPassword().trim())) {
            log.warn("Authentication failed: Password mismatch or password not set for email: {}", request.getEmail());
            throw new UnauthorizedException("Invalid email or password");
        }

        String jwt = jwtTokenProvider.generateToken(user);

        UserInfo userInfo = UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .userCode(user.getUserCode())
                .build();

        return AuthResponse.builder()
                .accessToken(jwt)
                .tokenType("Bearer")
                .expiresIn(jwtExpirationMs / 1000)
                .user(userInfo)
                .build();
    }


    public AuthResponse authenticateGoogleUser(GoogleLoginRequest request) {
        try {
            GoogleIdToken.Payload payload = googleTokenVerifier.verify(request.getIdToken());
            if (payload == null) {
                log.warn("Google token verification failed (invalid token)");
                throw new UnauthorizedException("Invalid Google ID token");
            }

            String email = payload.getEmail();
            log.info("Google token verified successfully for email: {}", email);

            User user = userRepository.findByEmailAndIsActiveTrue(email)
                    .orElseThrow(() -> {
                        log.warn("User email not found or inactive: {}", email);
                        return new UnauthorizedException("Email is not authorized or account is disabled");
                    });

            String jwt = jwtTokenProvider.generateToken(user);

            UserInfo userInfo = UserInfo.builder()
                    .id(user.getId())
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .role(user.getRole().name())
                    .userCode(user.getUserCode())
                    .build();

            return AuthResponse.builder()
                    .accessToken(jwt)
                    .tokenType("Bearer")
                    .expiresIn(jwtExpirationMs / 1000)
                    .user(userInfo)
                    .build();

        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error during Google authentication process", e);
            throw new UnauthorizedException("Authentication failed: " + e.getMessage());
        }
    }

    public UserInfo fetchCurrentUser(String email) {
        log.info("Fetching current user info for email: {}", email);
        User user = userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new UnauthorizedException("User session is invalid or account is deactivated"));
                
        return UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .userCode(user.getUserCode())
                .build();
    }
}
