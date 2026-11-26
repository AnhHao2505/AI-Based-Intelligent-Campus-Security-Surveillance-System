package com.fa26se040.icss.dto.systemconfig;

/**
 * Một lựa chọn hợp lệ cho khoá cấu hình dạng danh sách (trả từ Backend để Frontend không hardcode giá trị nghiệp vụ).
 */
public record ConfigOptionResponse(
        String value,
        String label,
        String description
) {
}
