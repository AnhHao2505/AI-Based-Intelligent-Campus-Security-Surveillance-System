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

    /**
     * Chuẩn hoá tên toà nhà (bỏ dấu tiếng Việt, bỏ tiền tố "tòa " / "toa ", chuyển chữ thường) để so khớp đồng nhất.
     */
    public static String normalizeBuildingName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(raw.trim().toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        if (normalized.startsWith("toa ")) {
            normalized = normalized.substring(4).trim();
        }
        return normalized;
    }

    /**
     * Kiểm tra hai tên toà nhà có trùng nhau sau chuẩn hoá hay không.
     */
    public static boolean matchesBuildingName(String b1, String b2) {
        String n1 = normalizeBuildingName(b1);
        String n2 = normalizeBuildingName(b2);
        return !n1.isEmpty() && n1.equals(n2);
    }
}
