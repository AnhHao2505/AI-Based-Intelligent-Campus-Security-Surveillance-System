package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.AuthResponse;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.dto.GoogleLoginRequest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.dto.ResetPasswordRequest;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.GoogleTokenVerifier;
import com.fa26se040.icss.security.JwtTokenProvider;
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
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final NotificationService notificationService;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    public void sendResetLink(ForgotPasswordRequest request) {
        String email = request.getEmail();
        log.info("Sending reset password link to email: {}", email);

        userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new UnauthorizedException("Email not found or account is deactivated"));

        String token = java.util.UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .email(email)
                .expiredAt(java.time.OffsetDateTime.now().plusMinutes(15))
                .build();

        passwordResetTokenRepository.save(resetToken);

        String resetLink = "http://localhost:5173/?token=" + token;
        notificationService.sendResetPasswordEmail(email, resetLink);
    }

    public void resetPassword(ResetPasswordRequest request) {
        log.info("Resetting password using token");
        PasswordResetToken token = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new UnauthorizedException("Mã token khôi phục mật khẩu không hợp lệ"));

        if (token.getIsUsed()) {
            throw new UnauthorizedException("Mã token này đã được sử dụng trước đó");
        }

        if (token.getExpiredAt().isBefore(java.time.OffsetDateTime.now())) {
            throw new UnauthorizedException("Mã token khôi phục mật khẩu đã hết hạn");
        }

        User user = userRepository.findByEmailAndIsActiveTrue(token.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Tài khoản liên kết với token này không tồn tại hoặc đã bị khóa"));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        token.setIsUsed(true);
        passwordResetTokenRepository.save(token);
        log.info("Password successfully reset for user: {}", token.getEmail());
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
