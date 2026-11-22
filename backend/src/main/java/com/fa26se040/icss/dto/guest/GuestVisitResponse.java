package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.enums.GuestVisitStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record GuestVisitResponse(
        UUID id,
        String hostCode,
        String hostName,
        String purpose,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        GuestVisitStatus status,
        Long version,
        List<GuestVisitAreaResponse> areas,
        List<GuestResponse> guests,
        String reviewedByName,
        OffsetDateTime reviewedAt,
        String reviewReason,
        String revokedByName,
        OffsetDateTime revokedAt,
        String revokeReason,
        OffsetDateTime cancelledAt,
        String cancelReason,
        // V137: người huỷ; cancelledBySystem = true khi hệ thống huỷ (vô hiệu hoá / đổi loại khu), false khi host tự huỷ,
        // null khi chưa huỷ hoặc lượt huỷ trước V137
        String cancelledByName,
        Boolean cancelledBySystem,
        OffsetDateTime closedAt,
        OffsetDateTime createdAt
) {
}
