package com.fa26se040.icss.dto.dashboard;

import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.SecurityStatsRange;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thống kê an ninh cho Dashboard (GET /api/dashboard/security-stats).
 *
 * @param incidentsByStatus  số sự cố có detectedAt trong khoảng, đủ 4 trạng thái (thiếu → 0)
 * @param resolvedCount      số sự cố đã kết luận (RESOLVED_VERIFIED + RESOLVED_DISMISSED)
 * @param dismissedRate      RESOLVED_DISMISSED / resolvedCount; null khi resolvedCount = 0
 * @param topAreas           tối đa 5 khu nhiều sự cố nhất; nhóm không gắn khu (areaId null) ở cuối nếu có
 * @param accessResultsByDay mỗi ngày trong khoảng một dòng, đủ 5 kết quả (thiếu → 0), ngày theo giờ Việt Nam
 */
public record SecurityStatsResponse(
        SecurityStatsRange range,
        OffsetDateTime from,
        OffsetDateTime to,
        Map<IncidentStatus, Long> incidentsByStatus,
        long resolvedCount,
        Double dismissedRate,
        List<AreaIncidentCount> topAreas,
        List<DailyAccessResults> accessResultsByDay
) {

    /** areaId và areaName null = sự cố không xác định khu vực. */
    public record AreaIncidentCount(UUID areaId, String areaName, long count) {
    }

    public record DailyAccessResults(LocalDate date, Map<AccessResult, Long> counts) {
    }
}
