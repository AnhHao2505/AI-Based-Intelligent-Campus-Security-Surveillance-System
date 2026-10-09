package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AccessControlErrorCode {
    ERR_AC_003("ERR_AC_003", HttpStatus.CONFLICT, "Dữ liệu cấu hình đã bị thay đổi bởi người khác. Vui lòng tải lại và thử lại."),
    ERR_AC_004("ERR_AC_004", HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình mặc định cho loại khu vực này"),
    ERR_AC_005("ERR_AC_005", HttpStatus.BAD_REQUEST, "Cờ yêu cầu chỉ định (explicitAuthorizationRequired) của preset được suy ra từ loại khu vực ({areaLevel}) và không được thay đổi độc lập"),
    // BR-RQ-06: người duyệt đơn truy cập phải khác người tạo đơn
    ERR_AC_006("ERR_AC_006", HttpStatus.FORBIDDEN, "Người tạo đơn không được tự duyệt hoặc tự từ chối đơn truy cập của chính mình"),
    // BR-RQ-46: chỉ chuyển sang Hoàn thành khi khung giờ của đơn đã bắt đầu; trước đó dùng Huỷ
    ERR_AC_007("ERR_AC_007", HttpStatus.BAD_REQUEST, "Đơn truy cập chưa đến giờ bắt đầu ({startTime}) nên chưa thể chuyển sang Hoàn thành"),
    // BR-RQ-47: FM chỉ huỷ đơn đã duyệt khi chưa tới giờ bắt đầu; đã bắt đầu thì dùng Hoàn thành
    ERR_AC_008("ERR_AC_008", HttpStatus.BAD_REQUEST, "Đơn truy cập đã bắt đầu ({startTime}) nên không thể huỷ, hãy dùng Hoàn thành");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AccessControlErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
