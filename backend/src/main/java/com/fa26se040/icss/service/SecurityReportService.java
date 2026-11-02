package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.report.SecurityReportSummaryDto;
import com.fa26se040.icss.dto.report.SecurityReportSummaryDto.*;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.AccessLogStatsQueryRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Service tổng hợp báo cáo an ninh chuyên sâu cho Ban Quản trị (Admin) và Quản lý cơ sở (Facility Manager).
 * Chế độ read-only, tổng hợp dữ liệu sự cố, kiểm soát ra vào, ca trực bảo vệ, camera và lưu lượng khách.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecurityReportService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CAMPUS_NAME = "FPT University TP.HCM — Cơ sở Tân Uyên";

    private final SecurityIncidentRepository securityIncidentRepository;
    private final AccessLogStatsQueryRepository accessLogStatsQueryRepository;
    private final GuardShiftRepository guardShiftRepository;
    private final CameraRepository cameraRepository;
    private final GuestVisitRepository guestVisitRepository;
    private final UserRepository userRepository;

    public SecurityReportSummaryDto generateReport(LocalDate startDate, LocalDate endDate, String userEmail) {
        log.info("Generating security surveillance report from [{}] to [{}] for user [{}]", startDate, endDate, userEmail);

        OffsetDateTime from = startDate.atStartOfDay(VN_ZONE).toOffsetDateTime();
        OffsetDateTime to = endDate.atTime(LocalTime.MAX).atZone(VN_ZONE).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(VN_ZONE);

        // 1. User metadata
        User adminUser = userEmail != null ? userRepository.findByEmail(userEmail).orElse(null) : null;
        String generatedByName = adminUser != null ? adminUser.getFullName() : "Administrator";
        String generatedByEmail = adminUser != null ? adminUser.getEmail() : (userEmail != null ? userEmail : "admin@fpt.edu.vn");

        // 2. Fetch incidents in period
        List<SecurityIncident> incidents = securityIncidentRepository.findIncidentsInPeriod(from, to);

        long totalIncidents = incidents.size();
        long verifiedIncidents = 0;
        long dismissedIncidents = 0;
        long pendingIncidents = 0;

        List<Long> responseTimes = new ArrayList<>();
        List<Long> resolutionTimes = new ArrayList<>();

        Map<String, Long> severityMap = new LinkedHashMap<>();
        for (IncidentSeverity s : IncidentSeverity.values()) {
            severityMap.put(s.name(), 0L);
        }

        Map<String, Long> eventTypeMap = new HashMap<>();
        Map<String, Long> resolutionCategoryMap = new HashMap<>();
        Map<String, Long> areaCountMap = new HashMap<>();
        Map<String, AreaIncidentStatDto> areaDtoMap = new HashMap<>();
        Map<UUID, GuardAggregate> guardMap = new HashMap<>();

        for (SecurityIncident inc : incidents) {
            // Status categorization
            if (inc.getOutcome() == IncidentOutcome.VERIFIED || inc.getStatus() == IncidentStatus.RESOLVED_VERIFIED) {
                verifiedIncidents++;
            } else if (inc.getOutcome() == IncidentOutcome.DISMISSED || inc.getStatus() == IncidentStatus.RESOLVED_DISMISSED) {
                dismissedIncidents++;
            } else if (inc.getStatus() == IncidentStatus.NEW || inc.getStatus() == IncidentStatus.CLAIMED) {
                pendingIncidents++;
            }

            // Response time: detectedAt -> claimedAt
            if (inc.getDetectedAt() != null && inc.getClaimedAt() != null && !inc.getClaimedAt().isBefore(inc.getDetectedAt())) {
                responseTimes.add(Duration.between(inc.getDetectedAt(), inc.getClaimedAt()).getSeconds());
            }

            // Resolution time: claimedAt -> resolvedAt
            if (inc.getClaimedAt() != null && inc.getResolvedAt() != null && !inc.getResolvedAt().isBefore(inc.getClaimedAt())) {
                resolutionTimes.add(Duration.between(inc.getClaimedAt(), inc.getResolvedAt()).getSeconds());
            }

            // Severity
            String sevKey = inc.getSeverity() != null ? inc.getSeverity().name() : "MEDIUM";
            severityMap.put(sevKey, severityMap.getOrDefault(sevKey, 0L) + 1L);

            // Event type
            String eventType = inc.getEventType() != null ? inc.getEventType() : "UNAUTHORIZED_ENTRY";
            eventTypeMap.put(eventType, eventTypeMap.getOrDefault(eventType, 0L) + 1L);

            // Resolution Category
            if (inc.getResolutionCategory() != null) {
                String resCat = inc.getResolutionCategory().name();
                resolutionCategoryMap.put(resCat, resolutionCategoryMap.getOrDefault(resCat, 0L) + 1L);
            }

            // Area aggregation
            String bld = inc.getBuilding() != null && !inc.getBuilding().isBlank() ? inc.getBuilding() : "Toàn trường";
            String areaName = inc.getArea() != null ? inc.getArea().getName() : "Khu vực chung (" + bld + ")";
            UUID areaId = inc.getArea() != null ? inc.getArea().getId() : null;
            areaCountMap.put(areaName, areaCountMap.getOrDefault(areaName, 0L) + 1L);
            areaDtoMap.putIfAbsent(areaName, new AreaIncidentStatDto(areaId, areaName, bld, 0L));

            // Guard performance
            if (inc.getResolvedBy() != null) {
                User g = inc.getResolvedBy();
                guardMap.computeIfAbsent(g.getId(), k -> new GuardAggregate(g.getId(), g.getFullName(), g.getUserCode(), 0L))
                        .increment();
            }
        }

        long resolvedCount = verifiedIncidents + dismissedIncidents;
        Double falseAlarmRate = resolvedCount > 0 ? (double) dismissedIncidents / resolvedCount : 0.0;
        Long avgResponseTimeSeconds = responseTimes.isEmpty() ? null : Math.round(responseTimes.stream().mapToLong(Long::longValue).average().orElse(0.0));
        Long avgResolutionTimeSeconds = resolutionTimes.isEmpty() ? null : Math.round(resolutionTimes.stream().mapToLong(Long::longValue).average().orElse(0.0));

        // Format severity counts
        List<CategoryCountDto> severityCounts = severityMap.entrySet().stream()
                .map(e -> new CategoryCountDto(e.getKey(), getSeverityLabel(e.getKey()), e.getValue(),
                        totalIncidents > 0 ? (double) e.getValue() / totalIncidents : 0.0))
                .toList();

        // Format event type counts
        List<CategoryCountDto> eventTypeCounts = eventTypeMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new CategoryCountDto(e.getKey(), getEventTypeLabel(e.getKey()), e.getValue(),
                        totalIncidents > 0 ? (double) e.getValue() / totalIncidents : 0.0))
                .toList();

        // Format resolution category counts
        List<CategoryCountDto> resolutionCategoryCounts = resolutionCategoryMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new CategoryCountDto(e.getKey(), getResolutionCategoryLabel(e.getKey()), e.getValue(),
                        resolvedCount > 0 ? (double) e.getValue() / resolvedCount : 0.0))
                .toList();

        // Top incident areas (top 5)
        List<AreaIncidentStatDto> topIncidentAreas = areaCountMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    AreaIncidentStatDto template = areaDtoMap.get(e.getKey());
                    return new AreaIncidentStatDto(
                            template != null ? template.areaId() : null,
                            e.getKey(),
                            template != null ? template.building() : "",
                            e.getValue()
                    );
                })
                .toList();

        // Top guards
        List<GuardIncidentStatDto> topGuards = guardMap.values().stream()
                .sorted(Comparator.comparingLong(GuardAggregate::getCount).reversed())
                .limit(5)
                .map(g -> new GuardIncidentStatDto(g.guardId, g.fullName, g.userCode, g.count))
                .toList();

        // Significant incidents: up to 20 incidents
        List<ReportIncidentItemDto> significantIncidents = incidents.stream()
                .limit(20)
                .map(inc -> new ReportIncidentItemDto(
                        inc.getId(),
                        inc.getDetectedAt(),
                        inc.getCameraCode(),
                        (inc.getBuilding() != null ? inc.getBuilding() + " · " : "") + (inc.getArea() != null ? inc.getArea().getName() : "Không xác định"),
                        getEventTypeLabel(inc.getEventType()),
                        inc.getSeverity() != null ? inc.getSeverity().name() : "MEDIUM",
                        getStatusLabel(inc.getStatus()),
                        inc.getSubjectUserCode() != null ? inc.getSubjectUserCode() : "Người lạ / Chưa rõ",
                        inc.getClaimedBy() != null ? inc.getClaimedBy().getFullName() : "—",
                        inc.getResolvedBy() != null ? inc.getResolvedBy().getFullName() : "—",
                        inc.getResolutionNotes() != null ? inc.getResolutionNotes() : "—",
                        inc.getResolutionCategory() != null ? getResolutionCategoryLabel(inc.getResolutionCategory().name()) : "—"
                ))
                .toList();

        // 3. Access logs aggregation
        Map<LocalDate, Map<AccessResult, Long>> accessByDay = new HashMap<>();
        Map<AccessResult, Long> accessTotals = new EnumMap<>(AccessResult.class);
        for (AccessResult r : AccessResult.values()) {
            accessTotals.put(r, 0L);
        }

        for (AccessLogStatsQueryRepository.DayResultCount row
                : accessLogStatsQueryRepository.countByDayAndResult(from, to, VN_ZONE.getId())) {
            try {
                AccessResult result = AccessResult.valueOf(row.getAccessResult());
                LocalDate day = LocalDate.parse(row.getDay());
                long count = row.getTotal() != null ? row.getTotal() : 0L;
                accessByDay.computeIfAbsent(day, d -> new EnumMap<>(AccessResult.class))
                        .merge(result, count, Long::sum);
                accessTotals.merge(result, count, Long::sum);
            } catch (Exception ex) {
                log.warn("Invalid access log result row: {}", row.getAccessResult());
            }
        }

        long totalAccessScans = accessTotals.values().stream().mapToLong(Long::longValue).sum();
        long authorizedAccessCount = accessTotals.getOrDefault(AccessResult.AUTHORIZED_ACCESS, 0L);
        long violationAccessCount = totalAccessScans - authorizedAccessCount;

        List<DailyAccessTrendDto> dailyAccessTrends = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            Map<AccessResult, Long> counts = accessByDay.getOrDefault(d, Map.of());
            long auth = counts.getOrDefault(AccessResult.AUTHORIZED_ACCESS, 0L);
            long unauth = counts.getOrDefault(AccessResult.UNAUTHORIZED_ACCESS, 0L);
            long outTime = counts.getOrDefault(AccessResult.ACCESS_OUT_OF_TIME, 0L);
            long unknown = counts.getOrDefault(AccessResult.UNKNOWN_PERSON, 0L);
            long intrusion = counts.getOrDefault(AccessResult.INTRUSION, 0L);
            long dayTotal = auth + unauth + outTime + unknown + intrusion;
            dailyAccessTrends.add(new DailyAccessTrendDto(d, auth, unauth, outTime, unknown, intrusion, dayTotal));
        }

        List<CategoryCountDto> accessResultCounts = accessTotals.entrySet().stream()
                .map(e -> new CategoryCountDto(e.getKey().name(), getAccessResultLabel(e.getKey()), e.getValue(),
                        totalAccessScans > 0 ? (double) e.getValue() / totalAccessScans : 0.0))
                .toList();

        // 4. Guard shift operations
        List<GuardShift> shifts = guardShiftRepository.findByShiftDateBetween(startDate, endDate);
        long scheduledShiftsCount = shifts.size();
        long completedShiftsCount = shifts.stream()
                .filter(s -> s.getStatus() == ShiftStatus.COMPLETED || s.getStatus() == ShiftStatus.CHECKED_IN)
                .count();
        Double shiftFulfillmentRate = scheduledShiftsCount > 0
                ? (double) completedShiftsCount / scheduledShiftsCount
                : 1.0;

        // 5. Cameras
        List<Camera> cameras = cameraRepository.findAll();
        long activeCamerasCount = cameras.stream().filter(c -> c.getDeletedAt() == null).count();
        long onlineCamerasCount = cameras.stream()
                .filter(c -> c.getDeletedAt() == null && c.getOperationalStatus() == OperationalStatus.ONLINE)
                .count();

        // 6. Guest visits
        long totalGuestVisits = guestVisitRepository.countVisitsInPeriod(from, to);
        long approvedGuestVisits = guestVisitRepository.countVisitsInPeriodWithStatuses(
                from, to, List.of(GuestVisitStatus.APPROVED, GuestVisitStatus.COMPLETED)
        );

        return new SecurityReportSummaryDto(
                startDate,
                endDate,
                now,
                generatedByName,
                generatedByEmail,
                CAMPUS_NAME,
                totalIncidents,
                verifiedIncidents,
                dismissedIncidents,
                pendingIncidents,
                falseAlarmRate,
                avgResponseTimeSeconds,
                avgResolutionTimeSeconds,
                totalAccessScans,
                authorizedAccessCount,
                violationAccessCount,
                activeCamerasCount,
                onlineCamerasCount,
                scheduledShiftsCount,
                completedShiftsCount,
                shiftFulfillmentRate,
                totalGuestVisits,
                approvedGuestVisits,
                severityCounts,
                eventTypeCounts,
                resolutionCategoryCounts,
                topIncidentAreas,
                accessResultCounts,
                dailyAccessTrends,
                topGuards,
                significantIncidents
        );
    }

    private static String getSeverityLabel(String sev) {
        if (sev == null) return "Trung bình";
        return switch (sev.toUpperCase()) {
            case "CRITICAL" -> "Nguy cấp";
            case "HIGH" -> "Cao";
            case "MEDIUM" -> "Trung bình";
            case "LOW" -> "Thấp";
            default -> sev;
        };
    }

    private static String getEventTypeLabel(String eventType) {
        if (eventType == null) return "Sự cố an ninh";
        return switch (eventType.toUpperCase()) {
            case "UNAUTHORIZED_ENTRY", "UNAUTHORIZED" -> "Đột nhập / Không thẩm quyền";
            case "UNKNOWN_PERSON", "UNKNOWN" -> "Người lạ / Không nhận diện";
            case "AFTER_HOURS_ACCESS", "AFTER_HOURS", "AFTER_HOURS_PRESENCE" -> "Truy cập ngoài giờ";
            case "LOITERING" -> "Lảng vảng bất thường";
            case "CROWD_OVERCROWDING" -> "Tụ tập đông người";
            case "CAMERA_TAMPERING" -> "Phá hoại camera / Làm mù";
            case "SUSPICIOUS_MOTION" -> "Chuyển động đáng ngờ";
            default -> eventType;
        };
    }

    private static String getResolutionCategoryLabel(String cat) {
        if (cat == null) return "Chưa xác định";
        return switch (cat.toUpperCase()) {
            case "REPORT_FILED" -> "Lập biên bản vi phạm";
            case "ESCORTED_OUT" -> "Áp giải ra ngoài";
            case "REMINDED_DISPERSED" -> "Nhắc nhở / Giải tán";
            case "AUTHORIZED_EXCEPTION" -> "Trường hợp ngoại lệ hợp lệ";
            case "FALSE_ALARM" -> "Báo động giả / Nhầm lẫn";
            case "DETAINED_ESCALATED" -> "Tạm giữ & chuyển cấp thẩm quyền";
            case "OTHER" -> "Xử lý khác";
            default -> cat;
        };
    }

    private static String getStatusLabel(IncidentStatus status) {
        if (status == null) return "—";
        return switch (status) {
            case NEW -> "Chờ xử lý";
            case CLAIMED -> "Đang xử lý";
            case RESOLVED_VERIFIED -> "Đã xác minh";
            case RESOLVED_DISMISSED -> "Báo động giả";
        };
    }

    private static String getAccessResultLabel(AccessResult res) {
        if (res == null) return "—";
        return switch (res) {
            case AUTHORIZED_ACCESS -> "Hợp lệ";
            case UNAUTHORIZED_ACCESS -> "Sai quyền truy cập";
            case ACCESS_OUT_OF_TIME -> "Ngoài giờ cho phép";
            case UNKNOWN_PERSON -> "Người không xác định";
            case INTRUSION -> "Xâm nhập trái phép";
        };
    }

    private static class GuardAggregate {
        final UUID guardId;
        final String fullName;
        final String userCode;
        long count;

        GuardAggregate(UUID guardId, String fullName, String userCode, long count) {
            this.guardId = guardId;
            this.fullName = fullName;
            this.userCode = userCode;
            this.count = count;
        }

        void increment() {
            this.count++;
        }

        long getCount() {
            return count;
        }
    }
}
