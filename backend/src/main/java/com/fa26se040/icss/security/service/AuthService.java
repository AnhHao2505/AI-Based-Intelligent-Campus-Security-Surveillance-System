package com.fa26se040.icss.security.service;

import com.fa26se040.icss.security.dto.AuthResponse;
import com.fa26se040.icss.security.dto.GoogleLoginRequest;
import com.fa26se040.icss.security.dto.UserInfo;
import com.fa26se040.icss.security.entity.User;
import com.fa26se040.icss.security.exception.UnauthorizedException;
import com.fa26se040.icss.security.repository.UserRepository;
import com.fa26se040.icss.security.security.GoogleTokenVerifier;
import com.fa26se040.icss.security.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

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
                    .staffCode(user.getStaffCode())
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
                .staffCode(user.getStaffCode())
                .build();
    }
}
