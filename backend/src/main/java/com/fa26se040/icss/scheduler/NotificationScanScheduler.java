package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Nhịp kỹ thuật mỗi phút cho 2 job thông báo đơn truy cập (V113). Chu kỳ của từng job đọc lại từ cấu hình hệ thống MỖI nhịp
 * nên sửa trên màn Cấu hình hệ thống có hiệu lực từ nhịp kế (≤ 1 phút), không restart:
 * <ul>
 *   <li>{@link Job#STARTING_SOON}: NOTIFICATION_STARTING_SOON_SCAN_MINUTES -> {@link InAppNotificationService#scanAndNotifyExpiringSoonRequests}</li>
 *   <li>{@link Job#PENDING_OVERDUE}: NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES -> {@link InAppNotificationService#scanAndNotifyPendingOverdueRequests}</li>
 * </ul>
 * Mỗi nhịp, job chạy khi (now − lần chạy trước) ≥ chu kỳ hiện hành. Lần chạy trước giữ trong bộ nhớ, làm tròn xuống phút
 * để nhịp trễ vài mili giây không làm lỡ một chu kỳ. Khởi động lại thì chạy ở nhịp đầu tiên; job idempotent nhờ chống trùng
 * existsByReferenceIdAndType. Lần chạy được ghi lại kể cả khi job lỗi (thử lại ở chu kỳ sau, không lặp lỗi mỗi phút).
 * Một job lỗi chỉ ghi log ERROR, không chặn job kia, không làm chết nhịp.
 *
 * <p>Cron của nhịp là tham số vận hành ({@code icss.scheduler.notification-scan-tick-cron}, mặc định mỗi phút); profile
 * test đặt "-" để tắt, test gọi thẳng {@link #tick(OffsetDateTime)}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScanScheduler {

    public enum Job { STARTING_SOON, PENDING_OVERDUE }

    private final InAppNotificationService inAppNotificationService;
    private final SystemConfigService systemConfigService;

    private final Map<Job, OffsetDateTime> lastRuns = new EnumMap<>(Job.class);

    @Scheduled(cron = "${icss.scheduler.notification-scan-tick-cron:0 * * * * *}")
    public void scheduledTick() {
        tick(OffsetDateTime.now());
    }

    /** Một nhịp. Trả về các job đã chạy ở nhịp này (theo thứ tự chạy). */
    public synchronized List<Job> tick(OffsetDateTime now) {
        List<Job> ran = new ArrayList<>();
        runIfDue(Job.STARTING_SOON, ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES, now,
                inAppNotificationService::scanAndNotifyExpiringSoonRequests, ran);
        runIfDue(Job.PENDING_OVERDUE, ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES, now,
                inAppNotificationService::scanAndNotifyPendingOverdueRequests, ran);
        return ran;
    }

    private void runIfDue(Job job, ConfigKey intervalKey, OffsetDateTime now, Consumer<OffsetDateTime> body, List<Job> ran) {
        try {
            OffsetDateTime tickMinute = now.truncatedTo(ChronoUnit.MINUTES);
            OffsetDateTime last = lastRuns.get(job);
            if (last != null) {
                int intervalMinutes = intervalMinutes(intervalKey);
                if (Duration.between(last, tickMinute).toMinutes() < intervalMinutes) {
                    return;
                }
            }
            lastRuns.put(job, tickMinute);
            ran.add(job);
            body.accept(now);
        } catch (Exception e) {
            log.error("[NOTIFICATION-SCAN] job {} lỗi: {}", job, e.getMessage(), e);
        }
    }

    private int intervalMinutes(ConfigKey key) {
        int minutes = systemConfigService.getInt(key);
        if (minutes <= 0) {
            int fallback = Integer.parseInt(key.getDefaultValue());
            log.warn("[NOTIFICATION-SCAN] {}={} không hợp lệ, dùng mặc định {}", key.getKey(), minutes, fallback);
            return fallback;
        }
        return minutes;
    }
}
