package com.fa26se040.icss.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Phân loại kết quả kiểm soát ra vào theo Ma trận kiểm soát an ninh (5 Tag chuẩn hóa).
 */
@Getter
@RequiredArgsConstructor
public enum AccessResult {
    // 5 Tag Chuẩn hóa toàn hệ thống
    AUTHORIZED_PERSON("Hợp lệ", "#10b981"),
    UNAUTHORIZED_PERSON("Không có quyền", "#ef4444"),
    UNKNOWN_PERSON("Đối tượng không xác định", "#8b5cf6"),
    PERSON_INTRUSION("Sau giờ hoạt động", "#ef4444"),
    SUSPICIOUS_MOVING_OBJECT("Chuyển động nghi vấn", "#f97316"),

    // Tương thích dữ liệu cũ trong CSDL (Legacy Compatibility)
    @Deprecated AUTHORIZED_ACCESS("Hợp lệ", "#10b981"),
    @Deprecated UNAUTHORIZED_ACCESS("Không đủ thẩm quyền", "#ef4444"),
    @Deprecated ACCESS_OUT_OF_TIME("Quá thời hạn thẩm quyền", "#f59e0b"),
    @Deprecated INTRUSION("Sau giờ hoạt động", "#eab308");

    private final String description;
    private final String colorCode;
}
