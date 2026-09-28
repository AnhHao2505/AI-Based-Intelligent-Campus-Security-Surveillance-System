package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.service.AreaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Job mỗi phút (BR-ES-17..19, ES-R) xử lý chế độ sự kiện:
 * 1. Đóng phiên hết hạn (runAsSystem("EVENT_MODE_EXPIRY"))
 * 2. Kích hoạt lịch sự kiện đến giờ (runAsSystem("EVENT_SCHEDULE_ACTIVATION"))
 * 3. Gửi thông báo nhắc nhở FM trước sự kiện
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AreaEventModeScheduler {

    private final AreaService areaService;

    @Scheduled(cron = "${icss.scheduler.event-mode-tick-cron:0 */1 * * * *}")
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();
        log.debug("AreaEventModeScheduler tick started at {}", now);

        try {
            // 1. Đóng phiên hết hạn
            areaService.processExpiredEventSessions(now);
        } catch (Exception e) {
            log.error("Error in processExpiredEventSessions tick: {}", e.getMessage(), e);
        }

        try {
            // 2. Kích hoạt lịch
            areaService.processScheduledEventActivations(now);
        } catch (Exception e) {
            log.error("Error in processScheduledEventActivations tick: {}", e.getMessage(), e);
        }

        try {
            // 3. Nhắc FM
            areaService.processScheduleReminders(now);
        } catch (Exception e) {
            log.error("Error in processScheduleReminders tick: {}", e.getMessage(), e);
        }
    }
}
