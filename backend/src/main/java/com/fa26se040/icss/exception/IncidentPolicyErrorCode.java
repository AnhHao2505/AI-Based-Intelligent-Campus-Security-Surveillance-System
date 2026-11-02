package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Mã lỗi màn chính sách sự cố (V90): luật severity, chính sách dispatch, danh mục vị trí trực.
 * {min}, {max}, {code}... được nội suy từ hằng / dữ liệu, không viết cứng con số.
 */
@Getter
public enum IncidentPolicyErrorCode {
    ERR_INCPOL_001("ERR_INCPOL_001", HttpStatus.NOT_FOUND, "Không tìm thấy luật mức độ sự cố"),
    ERR_INCPOL_002("ERR_INCPOL_002", HttpStatus.NOT_FOUND, "Không tìm thấy chính sách phân phối cho mức {severity}"),
    ERR_INCPOL_003("ERR_INCPOL_003", HttpStatus.NOT_FOUND, "Không tìm thấy vị trí trực {code}"),
    ERR_INCPOL_004("ERR_INCPOL_004", HttpStatus.CONFLICT, "Dữ liệu đã được người khác cập nhật, vui lòng tải lại"),
    ERR_INCPOL_005("ERR_INCPOL_005", HttpStatus.BAD_REQUEST, "Số bảo vệ được mời ở đợt 1 phải từ {min} đến {max}"),
    ERR_INCPOL_006("ERR_INCPOL_006", HttpStatus.BAD_REQUEST, "Thời gian chờ đợt 1 phải từ {min} đến {max} giây"),
    ERR_INCPOL_007("ERR_INCPOL_007", HttpStatus.BAD_REQUEST, "Thời gian chờ trước khi báo Quản lý CSVC phải từ {min} đến {max} phút"),
    ERR_INCPOL_008("ERR_INCPOL_008", HttpStatus.BAD_REQUEST, "Không thể ngừng sử dụng vị trí trực mặc định {code}");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    IncidentPolicyErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
