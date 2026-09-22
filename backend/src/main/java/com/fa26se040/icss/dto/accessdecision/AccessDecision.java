package com.fa26se040.icss.dto.accessdecision;

import com.fa26se040.icss.enums.AccessSource;

import java.util.UUID;

/**
 * Kết quả xét quyền ra vào của AccessDecisionService#checkEntry.
 *
 * @param allowed     true nếu được phép vào
 * @param source      nguồn cấp quyền; NONE khi bị từ chối
 * @param sourceRefId id bản ghi area_assigned_personnel hoặc id access_requests; null khi NONE
 * @param reason      mô tả ngắn lý do
 */
public record AccessDecision(
    boolean allowed,
    AccessSource source,
    UUID sourceRefId,
    String reason
) {
    public static AccessDecision denied(String reason) {
        return new AccessDecision(false, AccessSource.NONE, null, reason);
    }

    public static AccessDecision allowed(AccessSource source, UUID sourceRefId, String reason) {
        return new AccessDecision(true, source, sourceRefId, reason);
    }
}
