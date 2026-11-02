package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.AuthResponse;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.dto.GoogleLoginRequest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.dto.ResetPasswordRequest;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
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
    private final SystemConfigService systemConfigService;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /**
     * S2: không để lộ e-mail nào có tài khoản. Controller luôn trả cùng một câu; chỉ tài khoản tồn tại,
     * đang hoạt động và chưa xoá mới được tạo token và gửi mail. Lỗi gửi mail cũng không lộ ra ngoài.
     */
    public void sendResetLink(ForgotPasswordRequest request) {
        String email = request.getEmail();
        log.info("Sending reset password link to email: {}", email);

        boolean eligible = userRepository.findByEmailAndIsActiveTrue(email)
                .filter(u -> u.getDeletedAt() == null)
                .isPresent();
        if (!eligible) {
            log.info("Reset link not sent: no active account for the given email");
            return;
        }

        // M-03: hạn link lấy từ cấu hình lúc tạo token; mail ghi đúng số phút này
        int validMinutes = systemConfigService.getInt(ConfigKey.PASSWORD_RESET_TOKEN_MINUTES);
        String token = java.util.UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .email(email)
                .expiredAt(java.time.OffsetDateTime.now().plusMinutes(validMinutes))
                .build();

        passwordResetTokenRepository.save(resetToken);

        String baseUrl = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
        String resetLink = baseUrl + "/?token=" + token;
        // M-04b: gửi nền, request trả về ngay (vẫn cùng một phản hồi S2). Chỉ lỗi xếp hàng (pool đầy) mới về tới đây;
        // không log link hay token
        try {
            notificationService.sendResetPasswordEmail(email, resetLink, validMinutes);
        } catch (Exception e) {
            log.warn("Could not queue reset password email: {}", e.getClass().getSimpleName());
        }
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
        String email = request.getEmail() != null ? request.getEmail().trim() : "";
        log.info("Authenticating local user with email: {}", email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Authentication failed: Account not found for email: {}", email);
                    return new UnauthorizedException("Tài khoản không tồn tại trên hệ thống");
                });

        if (user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getIsActive())) {
            log.warn("Authentication failed: User account is inactive or deleted: {}", email);
            throw new UnauthorizedException("Tài khoản đã bị vô hiệu hóa hoặc không còn hoạt động");
        }

        if (user.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPassword().trim())) {
            log.warn("Authentication failed: Password mismatch for email: {}", email);
            throw new UnauthorizedException("Mật khẩu không chính xác");
        }

        String jwt = jwtTokenProvider.generateToken(user);

        UserInfo userInfo = UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .userCode(user.getUserCode())
                .accessLevel(user.getAccessLevel())
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

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        log.warn("User email not found: {}", email);
                        return new UnauthorizedException("Tài khoản không tồn tại trên hệ thống");
                    });

            if (user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getIsActive())) {
                log.warn("User email inactive or deleted: {}", email);
                throw new UnauthorizedException("Tài khoản đã bị vô hiệu hóa hoặc không còn hoạt động");
            }

            String jwt = jwtTokenProvider.generateToken(user);

            UserInfo userInfo = UserInfo.builder()
                    .id(user.getId())
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .role(user.getRole().name())
                    .userCode(user.getUserCode())
                    .accessLevel(user.getAccessLevel())
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
                .accessLevel(user.getAccessLevel())
                .build();
    }
}
