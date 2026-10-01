package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.exception.AreaErrorCode;
import java.util.List;
import java.util.UUID;

/**
 * Step 6 (BR-AD-08): xem trước vô hiệu hoá khu vực (chỉ đọc).
 * Cùng một hàm đánh giá với lúc thực thi POST /deactivate, nên hai bên cùng kết luận trên cùng dữ liệu.
 * blockers theo thứ tự: camera (009) -> sự cố mở (051) -> sự kiện đang bật (052) -> lịch chờ (042).
 * Các số *ToRevoke / *ToCancel là những gì hệ thống sẽ tự xử lý khi vô hiệu hoá.
 */
public record AreaDependencyResponse(
    UUID areaId,
    Long version,
    Boolean canDeactivate,
    List<Blocker> blockers,
    int apToRevoke,
    int requestsToCancel,
    int guestVisitsToCancel,
    int guestVisitsToRevoke,
    List<String> warnings,
    String note
) {
    public record Blocker(AreaErrorCode errorCode, Object count, String message) {}
}
