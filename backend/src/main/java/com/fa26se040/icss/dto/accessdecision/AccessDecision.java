package com.fa26se040.icss.dto.accessdecision;

import com.fa26se040.icss.enums.AccessSource;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Kết quả xét quyền ra vào của AccessDecisionService#checkEntry.
 *
 * @param allowed     true nếu được phép vào
 * @param source      nguồn cấp quyền; NONE khi bị từ chối
 * @param sourceRefId id bản ghi area_assigned_personnel hoặc id access_requests; null khi NONE
 * @param reason      mô tả ngắn lý do
 * @param expiresAt   thời điểm hết hạn hiệu lực (áp dụng cho đơn ra vào, khách, sự kiện, phân công)
 * @param startsAt    thời điểm bắt đầu có hiệu lực (áp dụng khi đơn/sự kiện chưa tới giờ)
 */
public record AccessDecision(
    boolean allowed,
    AccessSource source,
    UUID sourceRefId,
    String reason,
    OffsetDateTime expiresAt,
    OffsetDateTime startsAt
) {
    public AccessDecision(boolean allowed, AccessSource source, UUID sourceRefId, String reason) {
        this(allowed, source, sourceRefId, reason, null, null);
    }

    public AccessDecision(boolean allowed, AccessSource source, UUID sourceRefId, String reason, OffsetDateTime expiresAt) {
        this(allowed, source, sourceRefId, reason, expiresAt, null);
    }

    public static AccessDecision denied(String reason) {
        return new AccessDecision(false, AccessSource.NONE, null, reason, null, null);
    }

    public static AccessDecision denied(String reason, OffsetDateTime startsAt) {
        return new AccessDecision(false, AccessSource.NONE, null, reason, null, startsAt);
    }

    public static AccessDecision allowed(AccessSource source, UUID sourceRefId, String reason) {
        return new AccessDecision(true, source, sourceRefId, reason, null, null);
    }

    public static AccessDecision allowed(AccessSource source, UUID sourceRefId, String reason, OffsetDateTime expiresAt) {
        return new AccessDecision(true, source, sourceRefId, reason, expiresAt, null);
    }

    public static AccessDecision allowed(AccessSource source, UUID sourceRefId, String reason, OffsetDateTime expiresAt, OffsetDateTime startsAt) {
        return new AccessDecision(true, source, sourceRefId, reason, expiresAt, startsAt);
    }
}
