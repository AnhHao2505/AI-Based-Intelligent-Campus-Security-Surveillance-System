package com.fa26se040.icss.security;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PR-AU (BR-AU-32, BR mới khoá tạm): đếm lần đăng nhập sai theo e-mail đã chuẩn hoá (trim + lower), kể cả e-mail
 * không có tài khoản, để hành vi của e-mail có thật và không có thật giống hệt nhau.
 * Sai {@code LOGIN_MAX_FAILED_ATTEMPTS} lần trong {@code LOGIN_FAILED_WINDOW_MINUTES} phút -> khoá
 * {@code LOGIN_LOCK_MINUTES} phút. Trong thời gian khoá không đếm thêm (không kéo dài khoá, không báo lặp).
 * Ba tham số đọc qua SystemConfigService mỗi lần gọi nên sửa trên màn cấu hình có hiệu lực ngay.
 *
 * <p>Hạn chế đã biết (cùng MemberLookupRateLimiter): bộ đếm nằm trong RAM của một instance — mất khi restart,
 * không dùng chung được khi chạy nhiều instance.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptLimiter {

    private final SystemConfigService systemConfigService;
    private final ConcurrentHashMap<String, AttemptState> states = new ConcurrentHashMap<>();

    /** Đồng hồ thay được trong test để kiểm hết hạn khoá mà không sleep. */
    private volatile Clock clock = Clock.systemUTC();

    private static final class AttemptState {
        private final Deque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil;
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** E-mail (đã chuẩn hoá) đang trong đợt khoá. */
    public boolean isLocked(String key) {
        AttemptState state = states.get(key);
        if (state == null) {
            return false;
        }
        Instant now = clock.instant();
        synchronized (state) {
            clearExpiredLock(state, now);
            return state.lockedUntil != null;
        }
    }

    /**
     * Ghi một lần đăng nhập sai.
     *
     * @return true nếu lần sai này mở một đợt khoá mới (người gọi gửi cảnh báo đúng một lần cho đợt đó)
     */
    public boolean recordFailure(String key) {
        Instant now = clock.instant();
        int maxAttempts = systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        Duration window = Duration.ofMinutes(systemConfigService.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES));
        AttemptState state = states.computeIfAbsent(key, k -> new AttemptState());
        synchronized (state) {
            clearExpiredLock(state, now);
            if (state.lockedUntil != null) {
                return false;
            }
            Instant windowStart = now.minus(window);
            while (!state.failures.isEmpty() && state.failures.peekFirst().isBefore(windowStart)) {
                state.failures.pollFirst();
            }
            state.failures.addLast(now);
            if (state.failures.size() < maxAttempts) {
                return false;
            }
            int lockMinutes = systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES);
            state.lockedUntil = now.plus(Duration.ofMinutes(lockMinutes));
            state.failures.clear();
            log.warn("Login locked for {} minutes after {} failed attempts within {} minutes", lockMinutes, maxAttempts,
                    window.toMinutes());
            return true;
        }
    }

    /** Đăng nhập thành công: xoá bộ đếm của e-mail. */
    public void reset(String key) {
        states.remove(key);
    }

    private static void clearExpiredLock(AttemptState state, Instant now) {
        if (state.lockedUntil != null && !now.isBefore(state.lockedUntil)) {
            state.lockedUntil = null;
            state.failures.clear();
        }
    }

    /** Dọn mục không còn khoá và không còn lần sai nào trong cửa sổ, để giải phóng bộ nhớ. */
    @Scheduled(cron = "${icss.scheduler.rate-limiter-cleanup-cron:0 */5 * * * *}")
    public void cleanupOldEntries() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(Duration.ofMinutes(systemConfigService.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)));
        states.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            synchronized (state) {
                clearExpiredLock(state, now);
                while (!state.failures.isEmpty() && state.failures.peekFirst().isBefore(windowStart)) {
                    state.failures.pollFirst();
                }
                return state.lockedUntil == null && state.failures.isEmpty();
            }
        });
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    int trackedKeys() {
        return states.size();
    }
}
