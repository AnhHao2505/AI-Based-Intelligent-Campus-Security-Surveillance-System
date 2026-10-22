package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

/** V87: thêm title để Nhật ký kiểm toán hiện được tên sự kiện (audit cũ không có khoá này). */
public record AreaEventScheduleAuditSnapshot(
        UUID scheduleId,
        String title,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String status,
        String reasonCode,
        String reasonLabel,
        String note,
        String failReason
) implements AuditSnapshot {
    public AreaEventScheduleAuditSnapshot(
            UUID scheduleId,
            String title,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            String reasonCode,
            String reasonLabel,
            String note
    ) {
        this(scheduleId, title, startAt, endAt, status, reasonCode, reasonLabel, note, null);
    }
}
