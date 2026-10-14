package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.service.AccessLogRetentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Job dọn nhật ký ra vào hằng ngày. Cron là tham số vận hành ở application.yml (icss.scheduler.access-log-retention-cron);
 * số ngày giữ log là tham số nghiệp vụ ENTRY_LOG_RETENTION_DAYS trong system_configurations.
 */
@Component
@RequiredArgsConstructor
public class AccessLogRetentionScheduler {

    private final AccessLogRetentionService retentionService;

    @Scheduled(cron = "${icss.scheduler.access-log-retention-cron:0 30 2 * * *}")
    public void purge() {
        retentionService.purgeExpired(OffsetDateTime.now());
    }
}
