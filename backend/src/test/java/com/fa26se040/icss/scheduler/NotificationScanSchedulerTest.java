package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.scheduler.NotificationScanScheduler.Job;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V113: nhịp 1 phút chạy 2 job thông báo theo chu kỳ đọc từ cấu hình mỗi nhịp; job lỗi không chặn job kia. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationScanSchedulerTest {

    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private SystemConfigService systemConfigService;
    @InjectMocks private NotificationScanScheduler scheduler;

    private static final OffsetDateTime T0 = OffsetDateTime.of(2099, 3, 10, 11, 0, 0, 0, ZoneOffset.ofHours(7));

    @BeforeEach
    void setUp() {
        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES)).thenReturn(5);
        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES)).thenReturn(60);
    }

    @Test
    @DisplayName("Nhịp đầu tiên sau khởi động chạy cả 2 job, truyền đúng now")
    void firstTick_runsBoth() {
        assertEquals(List.of(Job.STARTING_SOON, Job.PENDING_OVERDUE), scheduler.tick(T0));
        verify(inAppNotificationService).scanAndNotifyExpiringSoonRequests(T0);
        verify(inAppNotificationService).scanAndNotifyPendingOverdueRequests(T0);
    }

    @Test
    @DisplayName("Chu kỳ đọc lại mỗi nhịp: đổi 5 -> 10 thì nhịp kế dùng 10, không cần khởi tạo lại bean")
    void intervalReadEveryTick() {
        scheduler.tick(T0);
        assertEquals(List.of(), scheduler.tick(T0.plusMinutes(4)));
        assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(T0.plusMinutes(5)));

        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES)).thenReturn(10);
        assertEquals(List.of(), scheduler.tick(T0.plusMinutes(10)));
        assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(T0.plusMinutes(15)));
        verify(inAppNotificationService, times(3)).scanAndNotifyExpiringSoonRequests(any());
    }

    @Test
    @DisplayName("Nhịp trễ vài trăm mili giây không làm lỡ chu kỳ (so theo phút)")
    void tickJitter_doesNotSkipCycle() {
        scheduler.tick(T0.plusNanos(900_000_000L));
        assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(T0.plusMinutes(5).plusNanos(1_000_000L)));
    }

    @Test
    @DisplayName("Job nhắc sắp đến giờ ném lỗi -> job tồn đọng vẫn chạy, nhịp không ném lỗi, chu kỳ sau thử lại")
    void failingJob_doesNotStopOther() {
        doThrow(new IllegalStateException("DB down")).when(inAppNotificationService).scanAndNotifyExpiringSoonRequests(any());

        assertDoesNotThrow(() -> scheduler.tick(T0));
        verify(inAppNotificationService).scanAndNotifyPendingOverdueRequests(T0);

        assertEquals(List.of(), scheduler.tick(T0.plusMinutes(1)), "lỗi không làm chạy lại mỗi phút");
        assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(T0.plusMinutes(5)));
        verify(inAppNotificationService, times(2)).scanAndNotifyExpiringSoonRequests(any());
    }

    @Test
    @DisplayName("Job tồn đọng ném lỗi -> không ảnh hưởng nhịp kế của job nhắc")
    void failingOverdueJob_doesNotStopStartingSoon() {
        doThrow(new IllegalStateException("boom")).when(inAppNotificationService).scanAndNotifyPendingOverdueRequests(any());
        scheduler.tick(T0);
        assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(T0.plusMinutes(5)));
        verify(inAppNotificationService, never()).scanAndNotifyPendingOverdueRequests(T0.plusMinutes(5));
    }
}
