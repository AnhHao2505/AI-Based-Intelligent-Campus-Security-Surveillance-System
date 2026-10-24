package com.fa26se040.icss.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Phân loại kết quả kiểm soát ra vào theo Ma trận kiểm soát an ninh.
 */
@Getter
@RequiredArgsConstructor
public enum AccessResult {
    AUTHORIZED_ACCESS("Hợp lệ", "#10b981"),
    UNAUTHORIZED_ACCESS("Không đủ thẩm quyền", "#ef4444"),
    ACCESS_OUT_OF_TIME("Quá thời hạn thẩm quyền", "#f59e0b"),
    UNKNOWN_PERSON("Đối tượng không xác định", "#8b5cf6"),
    INTRUSION("Sau giờ hoạt động", "#eab308");

    private final String description;
    private final String colorCode;
}
