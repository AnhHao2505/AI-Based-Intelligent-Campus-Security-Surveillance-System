package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.enums.GuestVisitStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Snapshot audit lượt khách (BR-GV-30). Không chứa họ tên khách, ảnh, embedding, URL. */
public record GuestVisitAuditSnapshot(
        GuestVisitStatus status,
        Long version,
        String hostCode,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        List<UUID> areaIds,
        int guestCount
) implements AuditSnapshot {
}
