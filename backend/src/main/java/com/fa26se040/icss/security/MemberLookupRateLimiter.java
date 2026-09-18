package com.fa26se040.icss.security;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.RateLimitExceededException;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Giới hạn tần suất tra cứu thông tin thành viên (Sliding Window Rate Limiter).
 *
 * Lưu ý quan trọng: Bộ đếm nằm trong bộ nhớ của một instance, không dùng được khi chạy
 * nhiều instance. Đây là hạn chế đã biết, không phải thiếu sót.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemberLookupRateLimiter {

    private final SystemConfigService systemConfigService;
    private final ConcurrentHashMap<String, Deque<Instant>> userRequestTimestamps = new ConcurrentHashMap<>();

    /**
     * Kiểm tra tần suất tra cứu của người dùng trong cửa sổ trượt 1 phút gần nhất.
     * Nếu vượt ngưỡng cấu hình (SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE) thì log WARN và ném RateLimitExceededException.
     *
     * @param userEmail Email của người dùng gửi yêu cầu
     * @throws RateLimitExceededException khi tần suất vượt quá giới hạn
     */
    public void checkRateLimit(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant oneMinuteAgo = now.minus(Duration.ofMinutes(1));
        int limit = systemConfigService.getInt(ConfigKey.SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE);

        Deque<Instant> timestamps = userRequestTimestamps.computeIfAbsent(userEmail, k -> new ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            // Loại bỏ các mốc thời gian ngoài cửa sổ 1 phút
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(oneMinuteAgo)) {
                timestamps.pollFirst();
            }

            // Kiểm tra số lần gọi trong cửa sổ
            if (timestamps.size() >= limit) {
                int totalAttempts = timestamps.size() + 1;
                log.warn("Cảnh báo vượt rate limit tra cứu thành viên: User [{}] đã gọi {} lần trong 1 phút (Ngưỡng cho phép: {})",
                        userEmail, totalAttempts, limit);
                throw new RateLimitExceededException("Bạn đã vượt quá số lần tra cứu thành viên cho phép ("
                        + limit + " lần/phút). Vui lòng thử lại sau.");
            }

            timestamps.addLast(now);
        }
    }

    /**
     * Dọn dẹp định kỳ các bản ghi cũ không còn hoạt động để giải phóng bộ nhớ.
     */
    @Scheduled(cron = "${icss.scheduler.rate-limiter-cleanup-cron:0 */5 * * * *}")
    public void cleanupOldEntries() {
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(5));
        userRequestTimestamps.entrySet().removeIf(entry -> {
            Deque<Instant> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().isBefore(fiveMinutesAgo)) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }
}
