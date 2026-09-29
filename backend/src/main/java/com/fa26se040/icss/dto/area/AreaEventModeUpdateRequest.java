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
}
