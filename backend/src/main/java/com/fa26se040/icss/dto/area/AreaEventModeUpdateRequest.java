package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.EventModeAction;

import java.time.OffsetDateTime;

public record AreaEventModeUpdateRequest(
        Boolean enabled,
        OffsetDateTime openUntil,
        String reasonCode,
        String note,
        // Step 5b (BR-EV-A1): ý định tường minh; enabled sẽ bị cấm ở hợp đồng mới
        EventModeAction action,
        // Step 5b (BR-TC-13): version của khu vực mà client đang xem
        Long version
) {
    public AreaEventModeUpdateRequest(Boolean enabled, OffsetDateTime openUntil, String reasonCode, String note) {
        this(enabled, openUntil, reasonCode, note, null, null);
    }

    public AreaEventModeUpdateRequest(Boolean enabled, OffsetDateTime openUntil, String reason) {
        this(enabled, openUntil, null, reason, null, null);
    }
}
