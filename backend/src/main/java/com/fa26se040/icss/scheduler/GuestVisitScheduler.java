package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.service.GuestVisitJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Tick job lượt khách (BR-GV-12, 25, 27, 29). Cron là tham số vận hành ở application.yml (icss.scheduler.guest-visit-tick-cron).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestVisitScheduler {

    private final GuestVisitJobService jobService;

    @Scheduled(cron = "${icss.scheduler.guest-visit-tick-cron:0 */1 * * * *}")
    public void tick() {
        jobService.runAll(OffsetDateTime.now());
    }
}
