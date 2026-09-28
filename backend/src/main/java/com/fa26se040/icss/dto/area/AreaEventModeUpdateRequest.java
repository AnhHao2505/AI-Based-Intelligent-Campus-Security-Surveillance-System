package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.EventModeAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.OffsetDateTime;

/**
 * Step 5b (BR-EV-A1): FM gửi ý định tường minh qua action (ENABLE / ADJUST / DISABLE) và version khu vực đang xem.
 * Trường "enabled" của hợp đồng cũ bị cấm: kiểu JsonNode để phân biệt không gửi (null) với gửi null (NullNode).
 */
@Builder
public record AreaEventModeUpdateRequest(
        JsonNode enabled,
        OffsetDateTime openUntil,
        String reasonCode,
        String note,
        EventModeAction action,
        Long version
) {
    /**
     * @deprecated hợp đồng cũ (enabled). Chỉ giữ để test cũ trước Step 5b biên dịch; service luôn trả 400 ERR_AREA_046.
     */
    @Deprecated(forRemoval = true)
    public AreaEventModeUpdateRequest(Boolean enabled, OffsetDateTime openUntil, String reasonCode, String note) {
        this(com.fasterxml.jackson.databind.node.BooleanNode.valueOf(Boolean.TRUE.equals(enabled)), openUntil, reasonCode, note, null, null);
    }

    /** @deprecated xem constructor 4 tham số. */
    @Deprecated(forRemoval = true)
    public AreaEventModeUpdateRequest(Boolean enabled, OffsetDateTime openUntil, String reason) {
        this(enabled, openUntil, null, reason);
    }
}
