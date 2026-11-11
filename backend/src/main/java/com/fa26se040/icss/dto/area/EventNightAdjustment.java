package com.fa26se040.icss.dto.area;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * EV-N8 (V115): một lịch / sự kiện bị hệ thống xử lý khi ADMIN đổi AI_AFTER_HOUR_START / AI_AFTER_HOUR_END.
 *
 * @param target    SCHEDULE (lịch chờ) hoặc EVENT (sự kiện đang mở)
 * @param targetId  id lịch (SCHEDULE) hoặc id phiên (EVENT)
 * @param outcome   TRIMMED (cắt giờ kết thúc), CANCELLED (huỷ lịch), STOPPED (tắt sự kiện đang mở)
 * @param newEndAt  giờ kết thúc mới khi TRIMMED; null khi CANCELLED / STOPPED
 */
public record EventNightAdjustment(
        UUID areaId,
        String areaName,
        String target,
        UUID targetId,
        String title,
        OffsetDateTime startAt,
        OffsetDateTime oldEndAt,
        OffsetDateTime newEndAt,
        String outcome
) {
    public static final String TARGET_SCHEDULE = "SCHEDULE";
    public static final String TARGET_EVENT = "EVENT";
    public static final String OUTCOME_TRIMMED = "TRIMMED";
    public static final String OUTCOME_CANCELLED = "CANCELLED";
    public static final String OUTCOME_STOPPED = "STOPPED";
}
