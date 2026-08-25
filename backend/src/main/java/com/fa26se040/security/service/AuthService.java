package com.fa26se040.security.service;

import com.fa26se040.security.dto.AuthResponse;
import com.fa26se040.security.dto.ForgotPasswordRequest;
import com.fa26se040.security.dto.GoogleLoginRequest;
import com.fa26se040.security.dto.LoginRequest;
import com.fa26se040.security.dto.RegisterRequest;
import com.fa26se040.security.dto.ResetPasswordRequest;
import com.fa26se040.security.dto.UserInfo;
import com.fa26se040.security.entity.PasswordResetToken;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.exception.UnauthorizedException;
import com.fa26se040.security.repository.PasswordResetTokenRepository;
import com.fa26se040.security.repository.UserRepository;
import com.fa26se040.security.security.GoogleTokenVerifier;
import com.fa26se040.security.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    private final JavaMailSender mailSender;

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
        sendResetEmail(email, resetLink);
    }

    private void sendResetEmail(String toEmail, String resetLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = 
                    new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("[Campus Security] Xác nhận khôi phục mật khẩu");

            String htmlContent = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                    + "<h2 style='color: #4A90E2; text-align: center;'>Khôi phục mật khẩu tài khoản</h2>"
                    + "<p>Xin chào,</p>"
                    + "<p>Bạn nhận được email này vì đã gửi yêu cầu khôi phục mật khẩu cho tài khoản Campus Security của mình.</p>"
                    + "<p>Vui lòng click vào nút bên dưới để tiến hành đổi mật khẩu. Đường dẫn này có hiệu lực trong vòng 15 phút:</p>"
                    + "<div style='text-align: center; margin: 30px 0;'>"
                    + "  <a href='" + resetLink + "' style='background-color: #4A90E2; color: white; padding: 12px 24px; text-decoration: none; border-radius: 5px; font-weight: bold;'>Đổi mật khẩu mới</a>"
                    + "</div>"
                    + "<p>Nếu link nút trên không hoạt động, bạn có thể copy link sau dán vào trình duyệt:</p>"
                    + "<p style='word-break: break-all;'><a href='" + resetLink + "'>" + resetLink + "</a></p>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin-top: 30px;' />"
                    + "<p style='font-size: 12px; color: #888;'>Nếu bạn không yêu cầu thay đổi mật khẩu này, hãy bỏ qua email này an toàn.</p>"
                    + "</div>";

            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Reset password email successfully sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send reset password email to {}", toEmail, e);
            log.warn("=== LOCAL DEVELOPMENT FALLBACK - RESET LINK: {} ===", resetLink);
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
