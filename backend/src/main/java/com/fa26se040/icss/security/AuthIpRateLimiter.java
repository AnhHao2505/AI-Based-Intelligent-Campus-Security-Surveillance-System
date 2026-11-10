package com.fa26se040.icss.security;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.RateLimitExceededException;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * PR-AU (BR mới giới hạn theo IP): cửa sổ trượt 1 phút theo địa chỉ IP cho các API xác thực không cần đăng nhập
 * (đăng nhập, đăng nhập Google, gửi link và đặt lại mật khẩu). Vượt {@code LOGIN_IP_RATE_PER_MINUTE} -> 429 với câu
 * không nhắc tới tài khoản. Ngưỡng đọc qua SystemConfigService mỗi lần gọi.
 *
 * <p>IP lấy từ {@code HttpServletRequest#getRemoteAddr()}: nếu bật {@code server.forward-headers-strategy} thì giá trị
 * này đã là IP gốc của client; chưa bật (hiện tại) thì là IP kết nối tới backend (sau proxy = IP proxy).
 * Không tự đọc X-Forwarded-For vì client giả được header này.</p>
 *
 * <p>Hạn chế đã biết: bộ đếm nằm trong RAM của một instance — mất khi restart, không dùng chung khi chạy nhiều instance.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthIpRateLimiter {

    static final String TOO_MANY_REQUESTS_MESSAGE = "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ít phút.";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final SystemConfigService systemConfigService;
    private final ConcurrentHashMap<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

    /** Đồng hồ thay được trong test. */
    private volatile Clock clock = Clock.systemUTC();

    /**
     * Ghi một lần gọi của IP; vượt ngưỡng thì ném {@link RateLimitExceededException} (429) và không ghi thêm.
     */
    public void check(String ip) {
        String key = (ip == null || ip.isBlank()) ? "unknown" : ip.trim();
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        int limit = systemConfigService.getInt(ConfigKey.LOGIN_IP_RATE_PER_MINUTE);
        Deque<Instant> timestamps = requestsByIp.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                log.warn("Auth endpoint rate limit exceeded for IP [{}]: limit {} per minute", key, limit);
                throw new RateLimitExceededException(TOO_MANY_REQUESTS_MESSAGE);
            }
            timestamps.addLast(now);
        }
    }

    /** Dọn IP không còn lần gọi nào trong cửa sổ 1 phút. */
    @Scheduled(cron = "${icss.scheduler.rate-limiter-cleanup-cron:0 */5 * * * *}")
    public void cleanupOldEntries() {
        Instant windowStart = clock.instant().minus(WINDOW);
        requestsByIp.entrySet().removeIf(entry -> {
            Deque<Instant> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    int trackedKeys() {
        return requestsByIp.size();
    }
}
