package com.fa26se040.icss.dto.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SecurityReportSummaryDto(
        LocalDate startDate,
        LocalDate endDate,
        OffsetDateTime generatedAt,
        String generatedByName,
        String generatedByEmail,
        String campusName,

        // Scorecard KPIs
        long totalIncidents,
        long verifiedIncidents,
        long dismissedIncidents,
        long pendingIncidents,
        Double falseAlarmRate,
        Long avgResponseTimeSeconds,
        Long avgResolutionTimeSeconds,

        long totalAccessScans,
        long authorizedAccessCount,
        long violationAccessCount,

        long activeCamerasCount,
        long onlineCamerasCount,

        long scheduledShiftsCount,
        long completedShiftsCount,
        Double shiftFulfillmentRate,

        long totalGuestVisits,
        long approvedGuestVisits,

        // Distributions & Details
        List<CategoryCountDto> severityCounts,
        List<CategoryCountDto> eventTypeCounts,
        List<CategoryCountDto> resolutionCategoryCounts,
        List<AreaIncidentStatDto> topIncidentAreas,
        List<CategoryCountDto> accessResultCounts,
        List<DailyAccessTrendDto> dailyAccessTrends,
        List<GuardIncidentStatDto> topGuards,
        List<ReportIncidentItemDto> significantIncidents
) {
    public record CategoryCountDto(String code, String label, long count, Double percentage) {}

    public record AreaIncidentStatDto(UUID areaId, String areaName, String building, long count) {}

    public record DailyAccessTrendDto(
            LocalDate date,
            long authorized,
            long unauthorized,
            long outOfTime,
            long unknownPerson,
            long intrusion,
            long total
    ) {}

    public record GuardIncidentStatDto(UUID guardId, String fullName, String userCode, long resolvedCount) {}

    public record ReportIncidentItemDto(
            UUID id,
            OffsetDateTime detectedAt,
            String cameraCode,
            String location,
            String eventType,
            String severity,
            String status,
            String subjectUserCode,
            String claimedByName,
            String resolvedByName,
            String resolutionNotes,
            String resolutionCategory
    ) {}
}

