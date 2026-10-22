package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;

/** V87: title bắt buộc (1–150 ký tự sau trim); note không bắt buộc khi tạo (BR-ES-20 sửa lại). */
public record EventScheduleRequest(
        String title,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String reasonCode,
        String note
) {
}
