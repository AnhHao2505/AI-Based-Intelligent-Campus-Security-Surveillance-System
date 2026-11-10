package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.AuthService;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fa26se040.icss.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T2 (20/10) + PR-AU (BR-AU-32 và BR khoá tạm / giới hạn IP): đăng nhập e-mail / mật khẩu và Google.
 * Mọi lần thất bại trả cùng status và cùng body (so sánh chuỗi JSON sau khi bỏ "timestamp" — ApiResponse luôn có
 * thời điểm tạo). Mật khẩu là chuỗi ngẫu nhiên sinh trong test; token chỉ kiểm có / không, không in ra.
 * Mỗi test gọi từ IP riêng để bộ đếm theo IP (RAM, dùng chung trong Spring context) không ảnh hưởng nhau.
 * Thời gian khoá điều khiển bằng {@link MutableTestClock} gắn vào {@link LoginAttemptLimiter}, không sleep.
 */
class AuthLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;

    // Cùng bộ mock với AuthPasswordResetIntegrationTest để dùng chung Spring context
    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String password = "T2-" + UUID.randomUUID();
    private User admin;

    @BeforeAll
    void setUpAdmin() {
        admin = newUser("adm", Role.ADMIN);
    }

    @AfterEach
    void restoreClock() {
        loginAttemptLimiter.setClock(Clock.systemUTC());
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("t2." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("T2-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("T2 " + tag + " " + suffix)
                .password(passwordEncoder.encode(password))
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static String unknownEmail() {
        return "t2.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn";
    }

    private static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private MvcResult login(String email, String pwd, String ip) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/login"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", pwd))))
                .andReturn();
    }

    private MvcResult googleLogin(String idToken, String ip) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/google"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + idToken + "\"}"))
                .andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Chuỗi body JSON đã bỏ "timestamp" (giá trị duy nhất khác nhau giữa hai lần gọi). */
    private String bodyWithoutTimestamp(MvcResult r) throws Exception {
        ObjectNode node = (ObjectNode) json(r);
        node.remove("timestamp");
        return objectMapper.writeValueAsString(node);
    }

    private int maxAttempts() {
        return systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
    }

    private void failTimes(String email, int times, String ip) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(email, password + "-wrong-" + i, ip).getResponse().getStatus()).isEqualTo(401);
        }
    }

    private int lockNotificationsFor(User recipient, User locked) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'ACCOUNT_LOCKED' "
                + "AND reference_id = ?", Integer.class, recipient.getId(), locked.getId());
    }

    private void assertGenericFailure(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = json(r);
        assertThat(body.path("message").asText()).isEqualTo(AuthService.LOGIN_FAILED_MESSAGE);
        assertThat(body.path("data").path("accessToken").isMissingNode()).isTrue();
    }

    // ------------------------------------------------------------------ đăng nhập thành công

    @Test
    @DisplayName("T2: đúng mật khẩu -> 200, có access token và thông tin user")
    void correctPassword_returnsToken() throws Exception {
        User user = newUser("ok", Role.NORMAL_USER);
        MvcResult r = login(user.getEmail(), password, randomIp());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("user").path("email").asText()).isEqualTo(user.getEmail());
    }

    // ------------------------------------------------------------------ BR-AU-32: cùng một phản hồi

    @Test
    @DisplayName("BR-AU-32: không tồn tại / sai mật khẩu / vô hiệu hoá + đúng mật khẩu / đã xoá + đúng mật khẩu / đang khoá + đúng mật khẩu -> cùng 401, body giống hệt")
    void allFailureCases_identicalStatusAndBody() throws Exception {
        User wrongPassword = newUser("bad", Role.NORMAL_USER);
        User deactivated = newUser("off", Role.NORMAL_USER);
        deactivated.setIsActive(false);
        userRepository.save(deactivated);
        User deleted = newUser("del", Role.NORMAL_USER);
        userService.softDelete(deleted.getId(), admin.getEmail());
        User locked = newUser("lck", Role.NORMAL_USER);
        failTimes(locked.getEmail(), maxAttempts(), randomIp());

        MvcResult unknown = login(unknownEmail(), password, randomIp());
        MvcResult badPassword = login(wrongPassword.getEmail(), password + "x", randomIp());
        MvcResult inactive = login(deactivated.getEmail(), password, randomIp());
        MvcResult softDeleted = login(deleted.getEmail(), password, randomIp());
        MvcResult lockedCorrect = login(locked.getEmail(), password, randomIp());

        assertGenericFailure(unknown);
        String expected = bodyWithoutTimestamp(unknown);
        for (MvcResult other : new MvcResult[]{badPassword, inactive, softDeleted, lockedCorrect}) {
            assertThat(other.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus());
            assertThat(bodyWithoutTimestamp(other)).isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------ khoá tạm theo e-mail

    @Test
    @DisplayName("Khoá tạm: sai N lần -> lần N+1 đúng mật khẩu vẫn 401 câu chung; hết LOGIN_LOCK_MINUTES -> đăng nhập được; thành công xoá bộ đếm")
    void lockAfterMaxFailures_unlockAfterLockMinutes_successResetsCounter() throws Exception {
        MutableTestClock clock = new MutableTestClock(Instant.now());
        loginAttemptLimiter.setClock(clock);
        User user = newUser("lock", Role.NORMAL_USER);
        String ip = randomIp();
        int n = maxAttempts();

        failTimes(user.getEmail(), n, ip);
        assertGenericFailure(login(user.getEmail(), password, ip));

        // Còn 1 giây nữa mới hết khoá -> vẫn bị từ chối
        clock.advance(Duration.ofMinutes(systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES)).minusSeconds(1));
        assertGenericFailure(login(user.getEmail(), password, ip));
        clock.advance(Duration.ofSeconds(1));
        assertThat(login(user.getEmail(), password, ip).getResponse().getStatus()).isEqualTo(200);

        // Thành công đã xoá bộ đếm: 2 lượt (N-1) lần sai, xen giữa là 1 lần đúng, không bao giờ bị khoá
        failTimes(user.getEmail(), n - 1, ip);
        assertThat(login(user.getEmail(), password, ip).getResponse().getStatus()).isEqualTo(200);
        failTimes(user.getEmail(), n - 1, ip);
        assertThat(login(user.getEmail(), password, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Khoá tạm: e-mail không có tài khoản cũng bị đếm và khoá giống hệt, không gửi mail")
    void unknownEmail_lockedTheSameWay_noNotification() throws Exception {
        String email = unknownEmail();
        String ip = randomIp();
        failTimes(email, maxAttempts(), ip);
        assertThat(loginAttemptLimiter.isLocked(LoginAttemptLimiter.normalize(email))).isTrue();
        // Chuẩn hoá trim + lower: khác hoa thường / khoảng trắng vẫn là cùng một bộ đếm
        assertThat(loginAttemptLimiter.isLocked(LoginAttemptLimiter.normalize("  " + email.toUpperCase(Locale.ROOT) + " "))).isTrue();
        assertGenericFailure(login(email, password, ip));
        verify(notificationService, never()).sendAccountLockedEmail(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Khoá tạm: đổi LOGIN_MAX_FAILED_ATTEMPTS 5 -> 2 qua SystemConfigService có hiệu lực ngay")
    void maxAttemptsChange_takesEffectImmediately() throws Exception {
        String previous = String.valueOf(maxAttempts());
        try {
            systemConfigService.update(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS.name(), "2", admin.getEmail());
            assertThat(maxAttempts()).isEqualTo(2);
            User user = newUser("cfg", Role.NORMAL_USER);
            String ip = randomIp();
            failTimes(user.getEmail(), 2, ip);
            assertGenericFailure(login(user.getEmail(), password, ip));
        } finally {
            systemConfigService.update(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS.name(), previous, admin.getEmail());
        }
    }

    @Test
    @DisplayName("Khoá tạm tài khoản có thật -> 1 thông báo ACCOUNT_LOCKED cho ADMIN + 1 mail cho chủ tài khoản; sai thêm trong đợt khoá không gửi lặp")
    void realAccountLocked_notifiesAdminAndOwnerOnce() throws Exception {
        User user = newUser("ntf", Role.NORMAL_USER);
        String ip = randomIp();
        int n = maxAttempts();

        failTimes(user.getEmail(), n - 1, ip);
        assertThat(lockNotificationsFor(admin, user)).isZero();
        verify(notificationService, never()).sendAccountLockedEmail(anyString(), anyString(), anyInt(), anyInt());

        failTimes(user.getEmail(), 1, ip);
        assertThat(lockNotificationsFor(admin, user)).isEqualTo(1);
        verify(notificationService, times(1)).sendAccountLockedEmail(eq(user.getEmail()), eq(user.getFullName()),
                eq(n), eq(systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES)));

        // Sai thêm (và đúng mật khẩu) trong đợt khoá: không đếm thêm, không gửi lại
        failTimes(user.getEmail(), 2, ip);
        assertGenericFailure(login(user.getEmail(), password, ip));
        assertThat(lockNotificationsFor(admin, user)).isEqualTo(1);
        verify(notificationService, times(1)).sendAccountLockedEmail(anyString(), anyString(), anyInt(), anyInt());
    }

    // ------------------------------------------------------------------ giới hạn theo IP

    @Test
    @DisplayName("Giới hạn IP: quá LOGIN_IP_RATE_PER_MINUTE lần gọi API xác thực từ 1 IP -> 429 câu không nhắc tài khoản; IP khác không bị ảnh hưởng")
    void ipRateLimit_returns429_otherIpUnaffected() throws Exception {
        int limit = systemConfigService.getInt(ConfigKey.LOGIN_IP_RATE_PER_MINUTE);
        String ip = randomIp();
        for (int i = 0; i < limit; i++) {
            assertThat(login(unknownEmail(), password, ip).getResponse().getStatus()).isEqualTo(401);
        }
        String email = unknownEmail();
        MvcResult blocked = login(email, password, ip);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        String message = json(blocked).path("message").asText();
        assertThat(message).isEqualTo(AuthIpRateLimiter.TOO_MANY_REQUESTS_MESSAGE);
        assertThat(message).doesNotContain(email).doesNotContainIgnoringCase("tài khoản");

        // Cùng bộ đếm cho cả 4 API: gửi link đặt lại mật khẩu từ IP đó cũng 429
        MvcResult resetLink = mockMvc.perform(fromIp(post("/api/auth/send-reset-link"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
        assertThat(resetLink.getResponse().getStatus()).isEqualTo(429);

        // IP khác vẫn được xử lý bình thường
        User user = newUser("ip", Role.NORMAL_USER);
        assertThat(login(user.getEmail(), password, randomIp()).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ Google

    private static GoogleIdToken.Payload payload(String email) {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setEmail(email);
        return p;
    }

    @Test
    @DisplayName("BR-AU-32 Google: chưa đăng ký / vô hiệu hoá / token sai / lỗi xác minh -> cùng 401, body giống hệt đăng nhập thường thất bại")
    void googleFailures_sameBodyAsLocalFailure() throws Exception {
        User deactivated = newUser("goff", Role.NORMAL_USER);
        deactivated.setIsActive(false);
        userRepository.save(deactivated);
        User active = newUser("gok", Role.NORMAL_USER);
        when(googleTokenVerifier.verify("tok-unregistered")).thenReturn(payload(unknownEmail()));
        when(googleTokenVerifier.verify("tok-deactivated")).thenReturn(payload(deactivated.getEmail()));
        when(googleTokenVerifier.verify("tok-invalid")).thenReturn(null);
        when(googleTokenVerifier.verify("tok-error")).thenThrow(new GeneralSecurityException("signature check failed"));
        when(googleTokenVerifier.verify("tok-active")).thenReturn(payload(active.getEmail()));

        String expected = bodyWithoutTimestamp(login(unknownEmail(), password, randomIp()));
        for (String token : new String[]{"tok-unregistered", "tok-deactivated", "tok-invalid", "tok-error"}) {
            MvcResult r = googleLogin(token, randomIp());
            assertGenericFailure(r);
            assertThat(bodyWithoutTimestamp(r)).as(token).isEqualTo(expected);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("signature");
        }

        MvcResult ok = googleLogin("tok-active", randomIp());
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ok).path("data").path("user").path("email").asText()).isEqualTo(active.getEmail());
    }
}
