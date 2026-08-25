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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    private static final String GENERIC_DENIED_MESSAGE = "Đăng nhập không thành công. Tài khoản chưa được cấp quyền truy cập hệ thống.";

    @Transactional
    public AuthResponse authenticateGoogleUser(GoogleLoginRequest request, HttpServletRequest httpServletRequest) {
        String clientIp = getClientIp(httpServletRequest);

        // 1. Verify Google ID token signature using official GoogleIdTokenVerifier
        GoogleIdToken.Payload payload = googleTokenVerifier.verify(request.getIdToken());
        if (payload == null) {
            log.warn("Google ID token verification failed");
            auditLogService.saveAuditLog(null, "LOGIN_DENIED", "Google ID token verification failed (invalid signature, audience, or malformed token)", clientIp);
            throw new UnauthorizedException("Đăng nhập không thành công. Google token không hợp lệ.");
        }

        String email = payload.getEmail();
        Boolean emailVerified = payload.getEmailVerified();
        String googleSub = payload.getSubject();

        // Lower log level to DEBUG and omit google_sub PII from info log
        log.debug("Google token verified for email: {}, emailVerified: {}", email, emailVerified);

        // 2. Check email_verified claim
        if (emailVerified == null || !emailVerified) {
            log.warn("Google login denied for email {}: email_verified claim is false or missing", email);
            auditLogService.saveAuditLog(null, "LOGIN_DENIED", "Google login denied for email " + email + ": email_verified claim is false or missing", clientIp);
            throw new UnauthorizedException(GENERIC_DENIED_MESSAGE);
        }

        // 3. Search user by google_sub first
        User user = null;
        if (googleSub != null && !googleSub.isBlank()) {
            Optional<User> userBySub = userRepository.findByGoogleSub(googleSub);
            if (userBySub.isPresent()) {
                User candidate = userBySub.get();
                if (!Boolean.TRUE.equals(candidate.getCanLogin()) || !Boolean.TRUE.equals(candidate.getIsActive()) || candidate.getDeletedAt() != null) {
                    log.warn("Google login denied for sub {}: account disabled, cannot login, or deleted", googleSub);
                    auditLogService.saveAuditLog(candidate.getId(), "LOGIN_DENIED", "Google login denied for email " + email + " (sub " + googleSub + "): Account disabled, cannot login, or deleted", clientIp);
                    throw new UnauthorizedException(GENERIC_DENIED_MESSAGE);
                }
                user = candidate;
            }
        }

        // 4. If not found by google_sub, search by lower(email) with condition: can_login = true AND is_active = true AND deleted_at IS NULL
        if (user == null && email != null) {
            Optional<User> userByEmail = userRepository.findActiveAuthorizedUserByEmail(email);
            if (userByEmail.isPresent()) {
                user = userByEmail.get();
            }
        }

        // 5. If not found / unauthorized -> 403 (UnauthorizedException), audit LOGIN_DENIED
        // ABSOLUTELY DO NOT create new user
        if (user == null) {
            log.warn("Google login denied for email {}: User email not found in whitelist or not allowed to login", email);
            auditLogService.saveAuditLog(null, "LOGIN_DENIED", "Google login denied for email " + email + ": User email not found in whitelist or not allowed to login", clientIp);
            throw new UnauthorizedException(GENERIC_DENIED_MESSAGE);
        }

        // 6. If found by email and google_sub is null, save sub to user
        if (user.getGoogleSub() == null && googleSub != null) {
            log.debug("Linking Google OAuth account to user {}", user.getUserCode());
            user.setGoogleSub(googleSub);
        }

        // 7. Update last_login_at & audit LOGIN_SUCCESS
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        saveAuditLog(user.getId(), "LOGIN_SUCCESS", "Google login successful for user " + user.getUserCode() + " (" + user.getEmail() + ")", clientIp);

        // 8. Issue JWT with role = users.role_type
        String jwt = jwtTokenProvider.generateToken(user);

        UserInfo userInfo = UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .roleType(user.getRoleType().name())
                .userCode(user.getUserCode())
                .build();

        return AuthResponse.builder()
                .accessToken(jwt)
                .tokenType("Bearer")
                .expiresIn(jwtExpirationMs / 1000)
                .user(userInfo)
                .build();
    }

    public UserInfo fetchCurrentUser(UUID userId) {
        log.debug("Fetching current user info for userId: {}", userId);
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ hoặc tài khoản không tồn tại."));

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            log.warn("User account is inactive: {}", userId);
            throw new UnauthorizedException("Tài khoản đã bị vô hiệu hóa.");
        }

        return UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .roleType(user.getRoleType().name())
                .userCode(user.getUserCode())
                .build();
    }

    private void saveAuditLog(UUID actorId, String actionType, String description, String ipAddress) {
        auditLogService.saveAuditLog(actorId, actionType, description, ipAddress);
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String ip = xForwardedFor.split(",")[0].trim();
            if (ip.length() > 45) {
                ip = ip.substring(0, 45);
            }
            return ip;
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && remoteAddr.length() > 45) {
            remoteAddr = remoteAddr.substring(0, 45);
        }
        return remoteAddr;
    }
}
