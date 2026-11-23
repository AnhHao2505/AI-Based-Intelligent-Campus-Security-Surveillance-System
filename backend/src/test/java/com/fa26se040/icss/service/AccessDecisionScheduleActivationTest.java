package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Việc 9 (không cần DB): AccessDecisionService.activateScheduleInBackground xếp tác vụ kích hoạt lịch sau khi transaction
 * của check-entry kết thúc, mỗi lịch tối đa một tác vụ đang chờ, chỉ lịch đã tới giờ và chưa hết giờ, lỗi không ném ra.
 * Executor giữ tác vụ lại để test tự chạy (thấy rõ "trước / sau").
 */
class AccessDecisionScheduleActivationTest {

    private AccessDecisionService service;
    private AreaService areaService;
    private final List<Runnable> queued = new ArrayList<>();
    private final UUID areaId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AccessDecisionService(null, null, null, null, null, null, null, null, null, null, null, null);
        areaService = mock(AreaService.class);
        ObjectProvider<AreaService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(areaService);
        ReflectionTestUtils.setField(service, "areaServiceProvider", provider);
        Executor holding = queued::add;
        ReflectionTestUtils.setField(service, "eventActivationExecutor", holding);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static AreaEventSchedule schedule(OffsetDateTime start, OffsetDateTime end) {
        return AreaEventSchedule.builder().id(UUID.randomUUID()).startAt(start).endAt(end)
                .status(AreaEventScheduleStatus.SCHEDULED).build();
    }

    private AreaEventSchedule due() {
        OffsetDateTime now = OffsetDateTime.now();
        return schedule(now.minusMinutes(5), now.plusHours(1));
    }

    @Test
    @DisplayName("EAU-01: trong transaction check-entry -> chưa xếp tác vụ; afterCompletion mới xếp; chạy tác vụ -> activateScheduleInTx đúng lịch")
    void queuedAfterTransactionCompletes() {
        AreaEventSchedule s = due();
        TransactionSynchronizationManager.initSynchronization();

        service.activateScheduleInBackground(s, areaId);

        assertThat(queued).as("chưa xếp khi check-entry chưa trả về").isEmpty();
        verify(areaService, never()).activateScheduleInTx(any(), any());
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(areaService, times(1)).activateScheduleInTx(eq(s.getId()), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("EAU-02: nhiều lượt vào khi tác vụ của lịch còn chờ -> chỉ 1 tác vụ; tác vụ xong thì lượt sau xếp được tiếp")
    void oneQueuedTaskPerSchedule() {
        AreaEventSchedule s = due();

        service.activateScheduleInBackground(s, areaId);
        service.activateScheduleInBackground(s, areaId);
        service.activateScheduleInBackground(s, areaId);
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        service.activateScheduleInBackground(s, areaId);
        assertThat(queued).hasSize(2);
    }

    @Test
    @DisplayName("EAU-03: lịch chưa tới giờ theo đồng hồ hiện tại hoặc đã hết giờ -> không kích hoạt (cùng điều kiện job)")
    void notDue_notQueued() {
        OffsetDateTime now = OffsetDateTime.now();
        service.activateScheduleInBackground(schedule(now.plusMinutes(10), now.plusHours(1)), areaId);
        service.activateScheduleInBackground(schedule(now.minusHours(2), now.minusMinutes(1)), areaId);
        service.activateScheduleInBackground(null, areaId);
        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("EAU-04: activateScheduleInTx ném lỗi -> tác vụ không ném ra ngoài, lịch được thả để lượt sau thử lại")
    void activationError_swallowed() {
        AreaEventSchedule s = due();
        doThrow(new IllegalStateException("DB lỗi")).when(areaService).activateScheduleInTx(any(), any());

        service.activateScheduleInBackground(s, areaId);
        assertThatCode(() -> queued.get(0).run()).doesNotThrowAnyException();

        service.activateScheduleInBackground(s, areaId);
        assertThat(queued).hasSize(2);
    }

    @Test
    @DisplayName("EAU-05: executor từ chối (hàng đầy) -> không ném lỗi vào check-entry, lịch được thả")
    void executorRejects_noException() {
        ReflectionTestUtils.setField(service, "eventActivationExecutor", (Executor) r -> {
            throw new RejectedExecutionException("hàng đầy");
        });
        AreaEventSchedule s = due();

        assertThatCode(() -> service.activateScheduleInBackground(s, areaId)).doesNotThrowAnyException();

        Executor holding = queued::add;
        ReflectionTestUtils.setField(service, "eventActivationExecutor", holding);
        service.activateScheduleInBackground(s, areaId);
        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("EAU-06: không có AreaService (test dựng tay, provider null) -> không làm gì")
    void noProvider_noop() {
        ReflectionTestUtils.setField(service, "areaServiceProvider", null);
        service.activateScheduleInBackground(due(), areaId);
        assertThat(queued).isEmpty();
    }
}
