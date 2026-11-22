package com.fa26se040.icss.dto.guest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Host tạo lượt khách (BR-GV-02..05, 15). Kiểm dữ liệu ở service để trả mã ERR_GUEST_0xx và nội suy config.
 * Gửi trong phần "data" của multipart; ảnh khách ở phần "photos" theo đúng thứ tự guests.
 * consentNoticeVersion: phiên bản thông báo đồng ý host đã cho khách xem (GUEST_CONSENT_NOTICE_VERSION).
 */
public record GuestVisitCreateRequest(
        String purpose,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        List<UUID> areaIds,
        List<GuestInput> guests,
        String consentNoticeVersion
) {
}
