package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AsyncConfig;
import com.fa26se040.icss.dto.AuthResponse;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.dto.GoogleLoginRequest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.dto.ResetPasswordRequest;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.GoogleTokenVerifier;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.security.LoginAttemptLimiter;
import com.fa26se040.icss.util.LogMasker;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * PR-AU (BR-AU-32): một câu duy nhất cho MỌI lần đăng nhập thất bại (local và Google): e-mail không có tài khoản,
     * sai mật khẩu, tài khoản vô hiệu hoá / đã xoá (kể cả nhập đúng mật khẩu), đang bị khoá tạm, Google chưa đăng ký,
     * token Google sai. Khác câu là dò được e-mail nào có tài khoản.
     */
    public static final String LOGIN_FAILED_MESSAGE =
            "Thông tin đăng nhập không đúng. Nếu bạn chắc chắn thông tin đúng, hãy liên hệ quản trị viên.";

    /** PR-AU: một câu cho mọi lỗi token đặt lại mật khẩu (không hợp lệ, đã dùng, hết hạn, tài khoản khoá / không tồn tại). */
    public static final String RESET_LINK_INVALID_MESSAGE = "Liên kết không hợp lệ hoặc đã hết hạn.";

    /** reference_type của thông báo ACCOUNT_LOCKED (reference_id = id tài khoản). */
    static final String REF_TYPE_USER = "USER";

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final InAppNotificationService inAppNotificationService;

    /**
     * PR-AU2 (R1): pool nền có sẵn (AsyncConfig, luồng "mail-"). Cảnh báo khoá tài khoản chạy trên pool này để phản hồi
     * đăng nhập không phải chờ ghi thông báo / xếp mail — nếu chờ, lần sai làm khoá của e-mail có thật sẽ chậm hơn
     * e-mail không có thật và lộ tài khoản qua thời gian phản hồi. Inject qua field để giữ nguyên constructor.
     */
    @Autowired
    @Qualifier(AsyncConfig.MAIL_EXECUTOR)
    private TaskExecutor lockNotificationExecutor;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /**
     * PR-AU (P2): hash BCrypt giả, tạo một lần lúc khởi động bằng chính PasswordEncoder của hệ thống (cùng cost với
     * hash thật). E-mail không có tài khoản vẫn chạy matches với hash này để thời gian phản hồi như e-mail có thật.
     */
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyPasswordHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * S2: không để lộ e-mail nào có tài khoản. Controller luôn trả cùng một câu; chỉ tài khoản tồn tại,
     * đang hoạt động và chưa xoá mới được tạo token và gửi mail. Lỗi gửi mail cũng không lộ ra ngoài.
     */
    public void sendResetLink(ForgotPasswordRequest request) {
        String email = request.getEmail();
        log.info("Sending reset password link to email: {}", LogMasker.maskEmail(email));

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
        // PR-AU (P7): mọi lỗi token trả cùng một câu, lý do cụ thể chỉ ghi log
        PasswordResetToken token = passwordResetTokenRepository.findByToken(request.getToken()).orElse(null);
        if (token == null) {
            log.warn("Password reset rejected: token not found");
            throw new UnauthorizedException(RESET_LINK_INVALID_MESSAGE);
        }

        if (Boolean.TRUE.equals(token.getIsUsed())) {
            log.warn("Password reset rejected: token already used");
            throw new UnauthorizedException(RESET_LINK_INVALID_MESSAGE);
        }

        if (token.getExpiredAt().isBefore(java.time.OffsetDateTime.now())) {
            log.warn("Password reset rejected: token expired");
            throw new UnauthorizedException(RESET_LINK_INVALID_MESSAGE);
        }

        User user = userRepository.findByEmailAndIsActiveTrue(token.getEmail()).orElse(null);
        if (user == null) {
            log.warn("Password reset rejected: account of the token is missing, deactivated or deleted");
            throw new UnauthorizedException(RESET_LINK_INVALID_MESSAGE);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        token.setIsUsed(true);
        passwordResetTokenRepository.save(token);
        log.info("Password successfully reset for user: {}", user.getId());
    }

    /**
     * PR-AU (BR-AU-32, P1–P3): mọi lần thất bại ném cùng một UnauthorizedException(LOGIN_FAILED_MESSAGE).
     * Thứ tự cố định để thời gian và hành vi không lộ trạng thái tài khoản:
     * 1. luôn chạy passwordEncoder.matches đúng một lần (e-mail không có tài khoản / đã xoá -> hash giả);
     * 2. đang khoá tạm -> từ chối (kể cả mật khẩu đúng), không đếm thêm;
     * 3. sai mật khẩu -> đếm theo e-mail đã chuẩn hoá (kể cả e-mail không có thật); mở đợt khoá mới với tài khoản có thật
     *    -> báo chủ tài khoản (mail) và mọi ADMIN đang hoạt động (in-app), một lần mỗi đợt;
     * 4. mật khẩu đúng nhưng tài khoản vô hiệu hoá -> từ chối;
     * 5. thành công -> xoá bộ đếm.
     */
    public AuthResponse authenticateLocalUser(LoginRequest request) {
        String key = LoginAttemptLimiter.normalize(request.getEmail());
        String rawPassword = request.getPassword() != null ? request.getPassword() : "";
        log.info("Authenticating local user with email: {}", LogMasker.maskEmail(key));

        User user = key.isEmpty() ? null : userRepository.findByEmail(key).orElse(null);
        boolean hasRealHash = user != null && user.getPassword() != null && !user.getPassword().isBlank();
        String hash = hasRealHash ? user.getPassword().trim() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hash) && hasRealHash;

        if (loginAttemptLimiter.isLocked(key)) {
            log.warn("Authentication failed: login temporarily locked for {}", who(user, key));
            throw loginFailed();
        }

        if (!passwordMatches) {
            boolean newlyLocked = loginAttemptLimiter.recordFailure(key);
            log.warn("Authentication failed: {} for {}", user == null ? "no account" : "wrong password", who(user, key));
            if (newlyLocked && user != null) {
                notifyAccountLockedAfterCommit(user);
            }
            throw loginFailed();
        }

        if (!isActiveAccount(user)) {
            log.warn("Authentication failed: account is inactive for {}", who(user, key));
            throw loginFailed();
        }

        loginAttemptLimiter.reset(key);
        return buildAuthResponse(user);
    }

    /**
     * PR-AU (BR-AU-32): Google chưa đăng ký, token sai, lỗi xác minh, tài khoản vô hiệu hoá / đã xoá hoặc đang khoá tạm
     * -> cùng câu LOGIN_FAILED_MESSAGE; không nối thông điệp lỗi kỹ thuật ra ngoài.
     */
    public AuthResponse authenticateGoogleUser(GoogleLoginRequest request) {
        String email;
        User user;
        try {
            GoogleIdToken.Payload payload = googleTokenVerifier.verify(request.getIdToken());
            if (payload == null) {
                log.warn("Google token verification failed (invalid token)");
                throw loginFailed();
            }
            email = payload.getEmail();
            log.info("Google token verified successfully for email: {}", LogMasker.maskEmail(email));
            user = email == null ? null : userRepository.findByEmail(email).orElse(null);
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error during Google authentication process", e);
            throw loginFailed();
        }

        String key = LoginAttemptLimiter.normalize(email);
        if (user == null) {
            log.warn("Google login failed: no account for email: {}", LogMasker.maskEmail(key));
            throw loginFailed();
        }
        if (!isActiveAccount(user)) {
            log.warn("Google login failed: account is inactive for {}", who(user, key));
            throw loginFailed();
        }
        if (loginAttemptLimiter.isLocked(key)) {
            log.warn("Google login failed: login temporarily locked for {}", who(user, key));
            throw loginFailed();
        }

        loginAttemptLimiter.reset(key);
        return buildAuthResponse(user);
    }

    private static UnauthorizedException loginFailed() {
        return new UnauthorizedException(LOGIN_FAILED_MESSAGE);
    }

    /** PR-AU2 (R2): đối tượng ghi log — userId nếu đã có tài khoản, ngược lại e-mail đã che. */
    private static String who(User user, String email) {
        return user != null && user.getId() != null ? "userId=" + user.getId() : "email=" + LogMasker.maskEmail(email);
    }

    private static boolean isActiveAccount(User user) {
        return user != null && user.getDeletedAt() == null && Boolean.TRUE.equals(user.getIsActive());
    }

    private AuthResponse buildAuthResponse(User user) {
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

    /**
     * PR-AU (P3) + PR-AU2 (R1): tài khoản có thật vừa bị khoá tạm -> mail cảnh báo cho chủ tài khoản và thông báo in-app
     * ACCOUNT_LOCKED cho mọi ADMIN đang hoạt động. TOÀN BỘ việc này chạy trên pool nền AsyncConfig.MAIL_EXECUTOR, kể cả khi
     * không có transaction: luồng request chỉ xếp task rồi trả 401 ngay. Đang trong transaction thì xếp task sau commit.
     * Lỗi trong task (hoặc pool từ chối nhận task) chỉ ghi log, không làm đổi phản hồi đăng nhập.
     */
    private void notifyAccountLockedAfterCommit(User user) {
        int attempts = systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        int lockMinutes = systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES);
        Runnable task = () -> {
            try {
                notificationService.sendAccountLockedEmail(user.getEmail(), user.getFullName(), attempts, lockMinutes);
            } catch (Exception e) {
                log.warn("Could not queue account locked email for userId={}: {}", user.getId(), e.getClass().getSimpleName());
            }
            try {
                List<User> admins = userRepository.findActiveUsersByRole(Role.ADMIN);
                String message = "Tài khoản " + user.getUserCode() + " (" + user.getEmail() + ") bị khoá đăng nhập "
                        + lockMinutes + " phút sau " + attempts + " lần nhập sai mật khẩu.";
                inAppNotificationService.createForUsers(admins, NotificationType.ACCOUNT_LOCKED,
                        "Tài khoản bị khoá đăng nhập tạm thời", message, user.getId(), REF_TYPE_USER);
            } catch (Exception e) {
                log.warn("Could not create ACCOUNT_LOCKED notification for userId={}: {}", user.getId(), e.getClass().getSimpleName());
            }
        };
        Runnable submit = () -> {
            try {
                lockNotificationExecutor.execute(task);
            } catch (Exception e) {
                log.warn("Could not schedule account locked notifications for userId={}: {}", user.getId(), e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    public UserInfo fetchCurrentUser(String email) {
        log.info("Fetching current user info for email: {}", LogMasker.maskEmail(email));
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
