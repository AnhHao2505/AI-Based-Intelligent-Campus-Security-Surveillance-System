package com.fa26se040.icss.exception;

import java.util.UUID;

/**
 * Câu báo chung cho lỗi không lường trước: người dùng chỉ thấy "Lỗi hệ thống. Mã tra cứu: &lt;8 ký tự&gt;", chi tiết (lớp
 * exception, message, stack) chỉ ghi log server kèm cùng mã để tra. Dùng chung cho response HTTP 500
 * (GlobalExceptionHandler.handleGeneral) và cột lỗi từng dòng của nạp tài khoản theo lô (UserBulkImportHelper).
 */
public final class SystemErrorReference {

    static final String MESSAGE_PREFIX = "Lỗi hệ thống. Mã tra cứu: ";

    private SystemErrorReference() {
    }

    /** Mã tra cứu mới: 8 ký tự hex đầu của một UUID ngẫu nhiên. */
    public static String newErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static String message(String errorId) {
        return MESSAGE_PREFIX + errorId;
    }
}
