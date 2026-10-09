package com.fa26se040.icss.dto.user;

import java.util.List;
import java.util.UUID;

/** BR-AL-28: kết quả đổi cấp theo từng người (UPDATED / UNCHANGED / FAILED kèm lý do). */
public record UserAccessLevelBulkUpdateResponse(
        int total,
        int updated,
        int unchanged,
        int failed,
        List<Item> results
) {
    public enum Outcome { UPDATED, UNCHANGED, FAILED }

    public record Item(
            UUID userId,
            String userCode,
            String fullName,
            Outcome outcome,
            Integer oldLevel,
            Integer newLevel,
            String message
    ) {}
}
