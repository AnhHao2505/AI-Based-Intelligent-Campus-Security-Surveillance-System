package com.fa26se040.icss.enums;

/**
 * Loại khu vực (V83, gộp 4 loại còn 3). Loại ⇔ cấp: cấp truy cập của khu luôn bằng cấp của loại,
 * không sửa riêng từng khu và không sửa theo preset.
 * INTERNAL_CONFIDENTIAL và CONFIDENTIAL_CONTACT_REQUIRED cũ đều thành CONFIDENTIAL; audit cũ vẫn giữ tên cũ dạng chuỗi.
 */
public enum AreaLevel {
    PUBLIC(1, "Công khai"),
    CONFIDENTIAL(2, "Bảo mật"),
    HIGHLY_CONFIDENTIAL(3, "Tuyệt mật");

    private final int level;
    private final String label;

    AreaLevel(int level, String label) {
        this.level = level;
        this.label = label;
    }

    /** Cấp truy cập của loại: Công khai 1, Bảo mật 2, Tuyệt mật 3. */
    public int level() {
        return level;
    }

    /** Tên loại hiển thị trong thông báo và câu lỗi. */
    public String label() {
        return label;
    }
}
