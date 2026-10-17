package com.fa26se040.icss.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Phân loại kết quả kiểm soát ra vào theo Ma trận kiểm soát an ninh.
 */
@Getter
@RequiredArgsConstructor
public enum AccessResult {
    AUTHORIZED_ACCESS("Truy cập hợp lệ", "#10b981"),
    UNAUTHORIZED_ACCESS("Truy cập sai thẩm quyền", "#ef4444"),
    ACCESS_OUT_OF_TIME("Truy cập ngoài khung giờ", "#f59e0b"),
    UNKNOWN_PERSON("Đối tượng không xác định", "#8b5cf6"),
    INTRUSION("Hiện diện sau giờ giới nghiêm", "#eab308");

    private final String description;
    private final String colorCode;
}
