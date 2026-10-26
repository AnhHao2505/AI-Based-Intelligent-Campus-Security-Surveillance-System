package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse.AreaIncidentCount;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse.DailyAccessResults;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.SecurityStatsRange;
import com.fa26se040.icss.repository.AccessLogStatsQueryRepository;
import com.fa26se040.icss.repository.SecurityStatsQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard thống kê an ninh cho FACILITY_MANAGER và ADMIN. CHỈ ĐỌC: không ghi sự cố, nhật ký ra vào hay
 * bất kỳ bảng nào khác.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecurityDashboardService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int TOP_AREA_LIMIT = 5;

    private final SecurityStatsQueryRepository securityStatsQueryRepository;
    private final AccessLogStatsQueryRepository accessLogStatsQueryRepository;

    /**
     * Khoảng [00:00 giờ Việt Nam của ngày đầu khoảng, now]; ngày đầu = hôm nay − (range.days − 1).
     *
     * @param now thời điểm tính (controller truyền thời điểm gọi API; test truyền mốc cố định)
     */
    public SecurityStatsResponse getStats(SecurityStatsRange range, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(VN_ZONE).toLocalDate();
        LocalDate firstDay = today.minusDays(range.getDays() - 1L);
        OffsetDateTime from = firstDay.atStartOfDay(VN_ZONE).toOffsetDateTime();

        Map<IncidentStatus, Long> byStatus = new EnumMap<>(IncidentStatus.class);
        for (IncidentStatus status : IncidentStatus.values()) {
            byStatus.put(status, 0L);
        }
        securityStatsQueryRepository.countByStatus(from, now)
                .forEach(row -> byStatus.put(row.getStatus(), row.getTotal()));

        long verified = byStatus.get(IncidentStatus.RESOLVED_VERIFIED);
        long dismissed = byStatus.get(IncidentStatus.RESOLVED_DISMISSED);
        long resolved = verified + dismissed;
        Double dismissedRate = resolved == 0 ? null : (double) dismissed / resolved;

        return new SecurityStatsResponse(
                range,
                from,
                now,
                byStatus,
                resolved,
                dismissedRate,
                topAreas(from, now),
                accessResultsByDay(firstDay, today, from, now)
        );
    }

    /** 5 khu nhiều sự cố nhất (repository đã sắp giảm dần, hoà theo tên); nhóm không gắn khu luôn ở cuối. */
    private List<AreaIncidentCount> topAreas(OffsetDateTime from, OffsetDateTime to) {
        List<AreaIncidentCount> result = new ArrayList<>();
        long unknownCount = 0;
        for (SecurityStatsQueryRepository.AreaCount row : securityStatsQueryRepository.countByArea(from, to)) {
            if (row.getAreaId() == null) {
                unknownCount += row.getTotal();
            } else if (result.size() < TOP_AREA_LIMIT) {
                result.add(new AreaIncidentCount(row.getAreaId(), row.getAreaName(), row.getTotal()));
            }
        }
        if (unknownCount > 0) {
            result.add(new AreaIncidentCount(null, null, unknownCount));
        }
        return result;
    }

    /** Mỗi ngày từ firstDay đến lastDay một dòng, đủ 5 kết quả; ngày không có lượt nào vẫn có dòng toàn 0. */
    private List<DailyAccessResults> accessResultsByDay(LocalDate firstDay, LocalDate lastDay,
                                                        OffsetDateTime from, OffsetDateTime to) {
        Map<LocalDate, Map<AccessResult, Long>> byDay = new HashMap<>();
        for (AccessLogStatsQueryRepository.DayResultCount row
                : accessLogStatsQueryRepository.countByDayAndResult(from, to, VN_ZONE.getId())) {
            AccessResult result;
            try {
                result = AccessResult.valueOf(row.getAccessResult());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("Bỏ qua kết quả ra vào không xác định '{}' khi thống kê Dashboard", row.getAccessResult());
                continue;
            }
            byDay.computeIfAbsent(LocalDate.parse(row.getDay()), d -> new EnumMap<>(AccessResult.class))
                    .merge(result, row.getTotal(), Long::sum);
        }

        List<DailyAccessResults> days = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            Map<AccessResult, Long> counts = new EnumMap<>(AccessResult.class);
            Map<AccessResult, Long> found = byDay.getOrDefault(day, Map.of());
            for (AccessResult result : AccessResult.values()) {
                counts.put(result, found.getOrDefault(result, 0L));
            }
            days.add(new DailyAccessResults(day, counts));
        }
        return days;
    }
}
