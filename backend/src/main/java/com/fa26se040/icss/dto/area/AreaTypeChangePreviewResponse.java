package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestType;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Step 5b (BR-TC-03): kết quả xem trước khi ADMIN đổi loại khu vực (chỉ đọc).
 * Cùng một hàm đánh giá với lúc thực thi PUT, nên hai bên cùng kết luận trên cùng dữ liệu.
 */
@Builder
public record AreaTypeChangePreviewResponse(
        UUID areaId,
        AreaLevel currentAreaLevel,
        AreaLevel newAreaLevel,
        Integer currentAreaAccessLevel,
        Integer newAreaAccessLevel,
        Boolean currentExplicitAuthorizationRequired,
        Boolean newExplicitAuthorizationRequired,
        int activeAssignedPersonnelCount,
        List<RequestItem> approvedRequestsToCancel,
        int pendingRequestsNotApprovableCount,
        List<RequestItem> pendingRequestsToCancel,
        boolean eventActive,
        int pendingScheduleCount,
        List<BlockingReason> blockingReasons
) {
    /** Một đơn bị ảnh hưởng: người gửi + khung giờ. */
    public record RequestItem(
            UUID id,
            RequestType requestType,
            String requesterCode,
            String requesterName,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
    }

    /** Lý do chặn đổi loại: mã lỗi + câu thông báo giống hệt lúc thực thi. */
    public record BlockingReason(String code, String message) {
    }
}
