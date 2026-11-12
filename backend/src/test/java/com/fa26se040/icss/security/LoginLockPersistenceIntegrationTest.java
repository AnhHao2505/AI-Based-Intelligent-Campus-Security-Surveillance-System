package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserLoginLockRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V114 (L3): khoá đăng nhập theo tài khoản lưu ở bảng users (failed_login_count, failed_login_window_start,
 * login_locked_until). Thời gian điều khiển bằng {@link MutableTestClock} gắn vào {@link LoginAttemptLimiter}, không sleep.
 * Mỗi test dùng tài khoản và IP riêng. Cùng bộ mock với AuthLoginIntegrationTest để dùng chung Spring context.
 */
class LoginLockPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;
    @Autowired private UserLoginLockRepository userLoginLockRepository;

    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String password = "L3-" + UUID.randomUUID();
    private User admin;
    private MutableTestClock clock;

    @BeforeAll
    void setUpAdmin() {
        admin = newUser("adm", Role.ADMIN);
    }

    @AfterEach
    void restoreClock() {
        loginAttemptLimiter.setClock(Clock.systemUTC());
    }

    private void useTestClock() {
        // Cột timestamptz lưu tới micro giây: làm tròn để so sánh mốc thời gian đọc lại từ DB
        clock = new MutableTestClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        loginAttemptLimiter.setClock(clock);
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("l3." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("L3-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("L3 " + tag + " " + suffix)
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

    private int login(String email, String pwd, String ip) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", pwd))))
                .andReturn();
        return r.getResponse().getStatus();
    }

    private void failTimes(User user, int times, String ip) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(user.getEmail(), password + "-wrong-" + i, ip)).isEqualTo(401);
        }
    }

    private Map<String, Object> lockColumns(User user) {
        return jdbc.queryForMap("SELECT failed_login_count, failed_login_window_start, login_locked_until FROM users WHERE id = ?",
                user.getId());
    }

    private int failedCount(User user) {
        return ((Number) lockColumns(user).get("failed_login_count")).intValue();
    }

    private Instant instant(Object timestamp) {
        return timestamp == null ? null : ((java.sql.Timestamp) timestamp).toInstant();
    }

    private int accountLockedAudits(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'AUTH' AND action = 'ACCOUNT_LOCKED' "
                + "AND subject_user_id = ?", Integer.class, user.getId());
    }

    private int maxAttempts() {
        return systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
    }

    private Duration lockDuration() {
        return Duration.ofMinutes(systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES));
    }

    // ------------------------------------------------------------------ a) sống qua restart, d) hết khoá -> mặc định

    @Test
    @DisplayName("L3-a/d: sai đủ ngưỡng -> khoá lưu DB; xoá RAM + tạo bean mới (mô phỏng restart) vẫn khoá tới login_locked_until; hết khoá đăng nhập đúng -> 3 cột về mặc định")
    void lockSurvivesRestart_thenSuccessResetsColumns() throws Exception {
        useTestClock();
        User user = newUser("rst", Role.NORMAL_USER);
        String ip = randomIp();
        int n = maxAttempts();
        Instant lockedAt = clock.instant();

        failTimes(user, n, ip);
        Map<String, Object> cols = lockColumns(user);
        assertThat(cols.get("failed_login_count")).isEqualTo(n);
        assertThat(instant(cols.get("login_locked_until"))).isEqualTo(lockedAt.plus(lockDuration()));
        assertThat(accountLockedAudits(user)).isEqualTo(1);

        // Mô phỏng restart: RAM của bean trống, một bean mới không có trạng thái nào
        loginAttemptLimiter.clearInMemoryState();
        assertThat(loginAttemptLimiter.trackedKeys()).isZero();
        LoginAttemptLimiter fresh = new LoginAttemptLimiter(systemConfigService, userLoginLockRepository);
        fresh.setClock(clock);
        assertThat(fresh.isAccountLocked(userRepository.findById(user.getId()).orElseThrow())).isTrue();
        assertThat(login(user.getEmail(), password, ip)).as("đúng mật khẩu trong đợt khoá").isEqualTo(401);

        clock.advance(lockDuration().minusSeconds(1));
        assertThat(fresh.isAccountLocked(userRepository.findById(user.getId()).orElseThrow())).isTrue();
        assertThat(login(user.getEmail(), password, ip)).isEqualTo(401);
        assertThat(failedCount(user)).as("trong đợt khoá không đếm thêm").isEqualTo(n);

        clock.advance(Duration.ofSeconds(1));
        assertThat(fresh.isAccountLocked(userRepository.findById(user.getId()).orElseThrow()))
                .as("login_locked_until <= now -> hết khoá").isFalse();
        assertThat(login(user.getEmail(), password, ip)).isEqualTo(200);
        cols = lockColumns(user);
        assertThat(cols.get("failed_login_count")).isEqualTo(0);
        assertThat(cols.get("failed_login_window_start")).isNull();
        assertThat(cols.get("login_locked_until")).isNull();
        assertThat(accountLockedAudits(user)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ b) đồng thời

    @Test
    @DisplayName("L3-b: 8 luồng ghi sai cùng lúc ở lần thứ ngưỡng (UPDATE nguyên tử) -> đúng 1 luồng mở đợt khoá")
    void concurrentFailuresAtThreshold_exactlyOneOpensLock() throws Exception {
        User user = newUser("cc", Role.NORMAL_USER);
        int n = maxAttempts();
        failTimes(user, n - 1, randomIp());
        User loaded = userRepository.findById(user.getId()).orElseThrow();

        List<Boolean> results = runConcurrently(8, () -> loginAttemptLimiter.recordAccountFailure(loaded));

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(failedCount(user)).isEqualTo(n);
        assertThat(lockColumns(user).get("login_locked_until")).isNotNull();
    }

    @Test
    @DisplayName("L3-b: 2 request đăng nhập sai đồng thời ở lần thứ ngưỡng -> cả hai 401, đúng 1 dòng ACCOUNT_LOCKED (lặp 5 tài khoản)")
    void concurrentWrongLogins_exactlyOneAccountLockedAudit() throws Exception {
        int n = maxAttempts();
        for (int round = 0; round < 5; round++) {
            User user = newUser("cl" + round, Role.NORMAL_USER);
            String ip = randomIp();
            failTimes(user, n - 1, ip);

            List<Integer> statuses = runConcurrently(2, () -> login(user.getEmail(), password + "-race", ip));

            assertThat(statuses).containsOnly(401);
            assertThat(accountLockedAudits(user)).as("round " + round).isEqualTo(1);
            assertThat(failedCount(user)).isEqualTo(n);
        }
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ c) cửa sổ hết hạn

    @Test
    @DisplayName("L3-c: cửa sổ hết hạn giữa chừng -> đếm lại từ 1, window_start = lần sai mới; phải đủ ngưỡng trong cửa sổ mới mới khoá")
    void windowExpires_countRestartsFromOne() throws Exception {
        useTestClock();
        User user = newUser("win", Role.NORMAL_USER);
        String ip = randomIp();
        int n = maxAttempts();
        Instant firstFailure = clock.instant();

        failTimes(user, n - 1, ip);
        assertThat(failedCount(user)).isEqualTo(n - 1);
        assertThat(instant(lockColumns(user).get("failed_login_window_start"))).isEqualTo(firstFailure);

        clock.advance(Duration.ofMinutes(systemConfigService.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)).plusSeconds(1));
        Instant restart = clock.instant();
        failTimes(user, 1, ip);
        Map<String, Object> cols = lockColumns(user);
        assertThat(cols.get("failed_login_count")).isEqualTo(1);
        assertThat(instant(cols.get("failed_login_window_start"))).isEqualTo(restart);
        assertThat(cols.get("login_locked_until")).isNull();

        // Tổng cộng đã sai 2n−2 lần nhưng cửa sổ mới mới có n−1 lần -> chưa khoá; lần thứ n của cửa sổ mới -> khoá
        failTimes(user, n - 2, ip);
        assertThat(failedCount(user)).isEqualTo(n - 1);
        assertThat(lockColumns(user).get("login_locked_until")).isNull();

        failTimes(user, 1, ip);
        assertThat(failedCount(user)).isEqualTo(n);
        assertThat(instant(lockColumns(user).get("login_locked_until"))).isEqualTo(restart.plus(lockDuration()));
    }

    // ------------------------------------------------------------------ e) đổi ngưỡng qua service

    @Test
    @DisplayName("L3-e: đã sai 1 lần (ngưỡng mặc định), đổi LOGIN_MAX_FAILED_ATTEMPTS -> 2 qua SystemConfigService -> lần sai kế khoá ngay")
    void maxAttemptsChange_appliesToNextFailure() throws Exception {
        String previous = String.valueOf(maxAttempts());
        User user = newUser("cfg", Role.NORMAL_USER);
        String ip = randomIp();
        try {
            failTimes(user, 1, ip);
            assertThat(lockColumns(user).get("login_locked_until")).isNull();

            systemConfigService.update(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS.name(), "2", admin.getEmail());
            failTimes(user, 1, ip);

            Map<String, Object> cols = lockColumns(user);
            assertThat(cols.get("failed_login_count")).isEqualTo(2);
            assertThat(cols.get("login_locked_until")).isNotNull();
            assertThat(accountLockedAudits(user)).isEqualTo(1);
            assertThat(login(user.getEmail(), password, ip)).isEqualTo(401);
        } finally {
            systemConfigService.update(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS.name(), previous, admin.getEmail());
        }
    }

    // ------------------------------------------------------------------ e-mail không có tài khoản: không ghi DB

    @Test
    @DisplayName("L3: e-mail không có tài khoản bị khoá trong RAM như cũ, không có dòng users nào được tạo / sửa")
    void unknownEmail_inMemoryOnly_noUserRow() throws Exception {
        String email = "l3.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn";
        String ip = randomIp();
        for (int i = 0; i < maxAttempts(); i++) {
            assertThat(login(email, password + i, ip)).isEqualTo(401);
        }
        assertThat(loginAttemptLimiter.isLocked(LoginAttemptLimiter.normalize(email))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Integer.class, email)).isZero();
    }

    // ------------------------------------------------------------------ entity chỉ đọc

    @Test
    @DisplayName("L3: save() một User đã nạp trước khi bị khoá không ghi đè login_locked_until (cột chỉ đọc trong JPA)")
    void staleEntitySave_doesNotClearLock() throws Exception {
        User user = newUser("stale", Role.NORMAL_USER);
        User stale = userRepository.findById(user.getId()).orElseThrow();
        failTimes(user, maxAttempts(), randomIp());
        OffsetDateTime lockedUntil = userRepository.findById(user.getId()).orElseThrow().getLoginLockedUntil();
        assertThat(lockedUntil).isNotNull();

        stale.setFullName(stale.getFullName() + " sửa");
        userRepository.save(stale);

        assertThat(lockColumns(user).get("login_locked_until")).isNotNull();
        assertThat(failedCount(user)).isEqualTo(maxAttempts());
    }
}
