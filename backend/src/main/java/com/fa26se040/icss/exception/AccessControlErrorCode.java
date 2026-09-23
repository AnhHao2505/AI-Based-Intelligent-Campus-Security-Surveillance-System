package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AccessControlErrorCode {
    ERR_AC_003("ERR_AC_003", HttpStatus.CONFLICT, "Dữ liệu cấu hình đã bị thay đổi bởi người khác. Vui lòng tải lại và thử lại."),
    ERR_AC_004("ERR_AC_004", HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình mặc định cho loại khu vực này");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AccessControlErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
