package com.fa26se040.icss.util;

/**
 * Tiện ích chuẩn hoá dữ liệu người dùng dùng chung giữa UserService và UserBulkImportHelper.
 */
public final class StringNormalizer {

    private StringNormalizer() {
        // Prevent instantiation
    }

    /**
     * Chuẩn hoá mã người dùng / cán bộ (loại bỏ khoảng trắng kể cả non-breaking space, chuyển thành chữ hoa).
     */
    public static String normCode(String code) {
        return code == null ? "" : code.replaceAll("[\\p{Z}\\s]+", "").trim().toUpperCase();
    }

    /**
     * Chuẩn hoá email (loại bỏ khoảng trắng kể cả non-breaking space, chuyển về chữ thường).
     */
    public static String normEmail(String email) {
        return email == null ? "" : email.replaceAll("[\\p{Z}\\s]+", "").trim().toLowerCase();
    }

    /**
     * Chuẩn hoá họ và tên (loại bỏ khoảng trắng đầu cuối, gộp các khoảng trắng kể cả non-breaking space thành 1 dấu cách).
     */
    public static String normName(String name) {
        return name == null ? "" : name.replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
}
