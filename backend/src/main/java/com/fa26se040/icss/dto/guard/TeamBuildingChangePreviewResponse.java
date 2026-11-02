package com.fa26se040.icss.dto.guard;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phản hồi xem trước điều kiện đổi toà nhà phụ trách của đội bảo vệ (BR-GB-06, §4.3).
 * Áp dụng theo mẫu blocker của AreaDependencyResponse (H10).
 */
public record TeamBuildingChangePreviewResponse(
        UUID teamId,
        UUID currentBuildingId,
        String currentBuildingName,
        UUID targetBuildingId,
        String targetBuildingName,
        Boolean canChange,
        List<Blocker> blockers
) {
    public record Blocker(
            String type,
            long count,
            String message,
            List<BlockerShiftDetail> shifts
    ) {}

    public record BlockerShiftDetail(
            UUID shiftId,
            UUID guardId,
            String guardName,
            String userCode,
            LocalDate shiftDate,
            String shiftType
    ) {}
}

