package com.fa26se040.icss.dto.guard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phản hồi báo cáo luân chuyển bảo vệ giữa các tòa nhà (BR-GB-08, §4.6).
 */
public record BuildingRotationReportResponse(
        LocalDate from,
        LocalDate to,
        List<BuildingSummaryDto> buildings,
        List<GuardRotationItemDto> items,
        int totalGuards,
        int guardsWithCrossBuildingShifts
) {
    public record BuildingSummaryDto(
            UUID buildingId,
            String buildingName
    ) {}

    public record GuardRotationItemDto(
            UUID guardId,
            String guardName,
            String guardCode,
            UUID teamId,
            String teamName,
            UUID homeBuildingId,
            String homeBuildingName,
            int totalShifts,
            int outsideTeamBuildingShifts,
            // Số ca không xác định được toà (không có toà của ca, khu vực không gắn tầng); không tính vào ngoài toà đội
            int unknownBuildingShifts,
            boolean hasNeverRotated,
            Map<String, Integer> shiftsByBuildingName
    ) {}
}

