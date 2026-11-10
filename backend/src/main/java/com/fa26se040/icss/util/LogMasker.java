package com.fa26se040.icss.util;

/**
 * PR-AU2: che dữ liệu cá nhân trước khi ghi log. Log không được chứa e-mail đầy đủ (đọc log là dò được e-mail nào
 * đã thử đăng nhập / có tài khoản). Khi đã biết tài khoản thì ghi userId thay vì e-mail.
 */
public final class LogMasker {

    private static final String MASK = "***";

    private LogMasker() {
    }

    /**
     * Giữ ký tự đầu của phần trước @ và nguyên tên miền: {@code hao@fpt.edu.vn -> h***@fpt.edu.vn}.
     * {@code null -> "null"}; chuỗi rỗng hoặc không có @ -> {@code "***"}; không có phần trước @ -> {@code "***@miền"}.
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return "null";
        }
        String value = email.trim();
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return MASK;
        }
        String domain = value.substring(at + 1);
        if (at == 0) {
            return MASK + "@" + domain;
        }
        return value.charAt(0) + MASK + "@" + domain;
    }
}
