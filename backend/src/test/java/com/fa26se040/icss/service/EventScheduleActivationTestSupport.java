package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.repository.AreaEventScheduleRepository;

import java.time.OffsetDateTime;

/**
 * X-01a: chế độ sự kiện chỉ mở qua lịch (job kích hoạt), không còn ENABLE / ADJUST thủ công.
 * Test cần một phiên sự kiện đang mở thì dựng đúng đường của job: lưu lịch SCHEDULED rồi gọi
 * {@link AreaService#activateScheduleInTx} (hàm job dùng cho từng lịch, REQUIRES_NEW, actor SYSTEM).
 * Chỉ kích hoạt lịch của chính test, không quét lịch sót của test khác như processScheduledEventActivations.
 */
final class EventScheduleActivationTestSupport {

    private EventScheduleActivationTestSupport() {
    }

    /**
     * Lịch [startAt, endAt) do {@code createdBy} đặt, kích hoạt tại now -> phiên đang mở (started_at = startAt,
     * planned_end = endAt), khu vực open_to_members = true, lịch STARTED. Trả lịch đã đọc lại sau khi kích hoạt.
     */
    static AreaEventSchedule openViaSchedule(AreaEventScheduleRepository scheduleRepository, AreaService areaService,
                                             Area area, User createdBy, String reasonCode, String reasonLabel,
                                             OffsetDateTime startAt, OffsetDateTime endAt) {
        AreaEventSchedule schedule = scheduleRepository.save(AreaEventSchedule.builder()
                .title("Sự kiện thử nghiệm")
                .area(area)
                .startAt(startAt)
                .endAt(endAt)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(reasonCode)
                .reasonLabel(reasonLabel)
                .note("Lịch dựng phiên sự kiện cho test (X-01a)")
                .createdBy(createdBy)
                .build());
        areaService.activateScheduleInTx(schedule.getId(), OffsetDateTime.now());
        AreaEventSchedule activated = scheduleRepository.findById(schedule.getId()).orElseThrow();
        if (activated.getStatus() != AreaEventScheduleStatus.STARTED) {
            throw new IllegalStateException("Lịch dựng phiên không kích hoạt được: " + activated.getStatus()
                    + " (" + activated.getFailReason() + ")");
        }
        return activated;
    }
}
