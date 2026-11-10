package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.GoogleTokenVerifier;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.security.LoginAttemptLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-AU (P2): e-mail không có tài khoản vẫn chạy passwordEncoder.matches đúng một lần, với hash BCrypt giả tạo lúc
 * khởi động bằng chính encoder của hệ thống (cùng cost) — thời gian phản hồi như e-mail có thật.
 * Mật khẩu luôn được kiểm trước trạng thái tài khoản / khoá tạm.
 */
class AuthServiceUniformFailureTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptLimiter loginAttemptLimiter;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = spy(new BCryptPasswordEncoder());
        loginAttemptLimiter = mock(LoginAttemptLimiter.class);
        authService = new AuthService(userRepository, mock(GoogleTokenVerifier.class), mock(JwtTokenProvider.class),
                passwordEncoder, mock(PasswordResetTokenRepository.class), mock(NotificationService.class),
                mock(SystemConfigService.class), loginAttemptLimiter, mock(InAppNotificationService.class));
        authService.initDummyPasswordHash();
    }

    private static LoginRequest request(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    private String dummyHash() {
        return (String) ReflectionTestUtils.getField(authService, "dummyPasswordHash");
    }

    @Test
    @DisplayName("P2: hash giả là BCrypt cùng cost với hash thật của encoder hệ thống")
    void dummyHash_sameCostAsRealHash() {
        String real = new BCryptPasswordEncoder().encode("any-password");
        assertThat(dummyHash()).isNotBlank();
        // "$2a$10$" — tiền tố gồm phiên bản và cost
        assertThat(dummyHash().substring(0, 7)).isEqualTo(real.substring(0, 7));
    }

    @Test
    @DisplayName("P2: e-mail không có tài khoản -> passwordEncoder.matches gọi đúng 1 lần với hash giả, rồi 401 câu chung")
    void unknownEmail_runsMatchesExactlyOnceAgainstDummyHash() {
        String email = "nobody-" + UUID.randomUUID() + "@fpt.edu.vn";
        // PR-AUDIT: đăng nhập tra một truy vấn gồm cả tài khoản đã xoá
        when(userRepository.findAllByEmailIncludingDeleted(email)).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> authService.authenticateLocalUser(request(email, "Secret-123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(AuthService.LOGIN_FAILED_MESSAGE);

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
        verify(passwordEncoder, times(1)).matches(eq("Secret-123"), eq(dummyHash()));
        verify(loginAttemptLimiter).recordFailure(email);
    }

    @Test
    @DisplayName("P2: tài khoản vô hiệu hoá nhập đúng mật khẩu -> vẫn kiểm mật khẩu (1 lần) rồi mới từ chối bằng câu chung")
    void deactivatedAccount_checksPasswordFirst() {
        String email = "off-" + UUID.randomUUID() + "@fpt.edu.vn";
        String hash = new BCryptPasswordEncoder().encode("Secret-123");
        User user = User.builder().id(UUID.randomUUID()).email(email).password(hash)
                .role(Role.NORMAL_USER).isActive(false).accessLevel(1).build();
        when(userRepository.findAllByEmailIncludingDeleted(email)).thenReturn(java.util.List.of(user));

        assertThatThrownBy(() -> authService.authenticateLocalUser(request(email, "Secret-123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(AuthService.LOGIN_FAILED_MESSAGE);

        verify(passwordEncoder, times(1)).matches(eq("Secret-123"), eq(hash));
    }
}
