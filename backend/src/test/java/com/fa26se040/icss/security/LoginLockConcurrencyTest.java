package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.LoginRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.UserLoginLockRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.AuthService;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-05 (kiểm lại trên main sau V114): khoá đăng nhập khi request chạy song song.
 * (a) Loạt N request sai cùng lúc: trước BE-05 mọi request đều qua bước kiểm khoá (đọc trước bcrypt) nên được báo kết
 *     quả mật khẩu — thử được N mật khẩu dù ngưỡng là LOGIN_MAX_FAILED_ATTEMPTS. Nay chỉ đúng ngưỡng request được thử.
 * (b) Request đúng mật khẩu chạy đúng lúc lần sai cuối đang khoá: trước BE-05 vẫn đăng nhập được và xoá luôn dấu khoá.
 */
class LoginLockConcurrencyTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserLoginLockRepository userLoginLockRepository;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    // Cùng bộ mock với LoginLockPersistenceIntegrationTest để dùng chung Spring context
    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String password = "Be05-" + UUID.randomUUID();

    private User newUser(String tag) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("be05." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("BE05-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("BE05 " + tag + " " + suffix)
                .password(passwordEncoder.encode(password))
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private int maxAttempts() {
        return systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
    }

    /** "OK" hoặc "401". */
    private String login(User user, String pwd) {
        LoginRequest r = new LoginRequest();
        r.setEmail(user.getEmail());
        r.setPassword(pwd);
        try {
            authService.authenticateLocalUser(r);
            return "OK";
        } catch (UnauthorizedException e) {
            return "401";
        }
    }

    private int loginFailedAudits(User user, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'AUTH' AND action = 'LOGIN_FAILED' "
                + "AND subject_user_id = ? AND new_value ->> 'failureReason' = ?", Integer.class, user.getId(), reason);
    }

    private int audits(User user, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'AUTH' AND action = ? "
                + "AND subject_user_id = ?", Integer.class, action, user.getId());
    }

    private int failedCount(User user) {
        return jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, user.getId());
    }

    private Object lockedUntil(User user) {
        return jdbc.queryForObject("SELECT login_locked_until FROM users WHERE id = ?", Object.class, user.getId());
    }

    @Test
    @DisplayName("(a) 15 request sai cùng lúc -> đúng LOGIN_MAX_FAILED_ATTEMPTS request được thử mật khẩu (BAD_PASSWORD), còn lại LOCKED; 1 ACCOUNT_LOCKED (lặp 3 tài khoản)")
    void burstOfWrongLogins_onlyThresholdAttemptsEvaluated() throws Exception {
        int n = maxAttempts();
        int burst = 15;
        for (int round = 0; round < 3; round++) {
            User user = newUser("burst" + round);
            ExecutorService pool = Executors.newFixedThreadPool(burst);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < burst; i++) {
                    String guess = password + "-guess-" + i;
                    futures.add(pool.submit(() -> {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        return login(user, guess);
                    }));
                }
                start.countDown();
                for (Future<String> f : futures) {
                    assertThat(f.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("401");
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(loginFailedAudits(user, "BAD_PASSWORD")).as("round " + round + ": số mật khẩu được thử").isEqualTo(n);
            assertThat(loginFailedAudits(user, "LOCKED")).as("round " + round).isEqualTo(burst - n);
            assertThat(failedCount(user)).isEqualTo(n);
            assertThat(lockedUntil(user)).isNotNull();
            assertThat(audits(user, "ACCOUNT_LOCKED")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("(b) Lần sai cuối đang khoá tài khoản (chưa commit) -> request đúng mật khẩu chờ, rồi bị từ chối; dấu khoá còn nguyên")
    void correctPasswordWhileLastFailureLocks_rejected() throws Exception {
        int n = maxAttempts();
        User user = newUser("race");
        for (int i = 0; i < n - 1; i++) {
            assertThat(login(user, password + "-wrong-" + i)).isEqualTo("401");
        }
        assertThat(failedCount(user)).isEqualTo(n - 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockedNotCommitted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        int[] pid = new int[1];
        try {
            // Lần sai thứ n của request khác: giữ lượt + mở khoá, giữ giao dịch mở
            Future<?> lastFailure = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                OffsetDateTime now = OffsetDateTime.now();
                assertThat(userLoginLockRepository.reserveAttempt(user.getId(), now, now.minusMinutes(15), n).getAsInt()).isEqualTo(n);
                assertThat(userLoginLockRepository.lockIfThresholdReached(user.getId(), n, now.plusMinutes(15))).isTrue();
                pid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                lockedNotCommitted.countDown();
                try {
                    assertThat(commit.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(lockedNotCommitted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            Future<String> correct = pool.submit(() -> login(user, password));
            boolean blocked = waitUntil(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, pid[0]) > 0);
            commit.countDown();
            lastFailure.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String result = correct.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(blocked).as("request đúng mật khẩu không chờ khoá dòng users").isTrue();
            assertThat(result).as("đúng mật khẩu lúc tài khoản vừa bị khoá").isEqualTo("401");
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        assertThat(lockedUntil(user)).as("dấu khoá không bị xoá").isNotNull();
        assertThat(failedCount(user)).isEqualTo(n);
        assertThat(audits(user, "LOGIN_SUCCESS")).isZero();
        assertThat(loginFailedAudits(user, "LOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("Tuần tự như cũ: n−1 lần sai rồi đúng mật khẩu -> đăng nhập được, bộ đếm về 0")
    void sequential_correctAfterFailures_stillSucceeds() {
        int n = maxAttempts();
        User user = newUser("seq");
        for (int i = 0; i < n - 1; i++) {
            assertThat(login(user, password + "-wrong-" + i)).isEqualTo("401");
        }
        assertThat(login(user, password)).isEqualTo("OK");
        assertThat(failedCount(user)).isZero();
        assertThat(lockedUntil(user)).isNull();
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
