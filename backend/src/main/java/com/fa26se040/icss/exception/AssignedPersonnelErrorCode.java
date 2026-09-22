package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AssignedPersonnelErrorCode {
    ERR_AP_001("ERR_AP_001", HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi gán nhân sự trong khu vực này"),
    ERR_AP_002("ERR_AP_002", HttpStatus.BAD_REQUEST, "Thời điểm kết thúc phải sau thời điểm bắt đầu"),
    ERR_AP_003("ERR_AP_003", HttpStatus.BAD_REQUEST, "Thời điểm kết thúc phải ở tương lai. Muốn kết thúc ngay, hãy dùng chức năng thu hồi"),
    ERR_AP_004("ERR_AP_004", HttpStatus.CONFLICT, "Người dùng đã có bản ghi gán chồng lấn thời gian tại khu vực này"),
    ERR_AP_005("ERR_AP_005", HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"),
    ERR_AP_006("ERR_AP_006", HttpStatus.BAD_REQUEST, "Không thể gán người dùng đã bị vô hiệu hoá hoặc đã bị xoá"),
    ERR_AP_007("ERR_AP_007", HttpStatus.BAD_REQUEST, "Không thể gán vào khu vực đã ngừng hoạt động hoặc đã bị xoá"),
    ERR_AP_008("ERR_AP_008", HttpStatus.BAD_REQUEST, "Lý do thu hồi không được để trống"),
    ERR_AP_009("ERR_AP_009", HttpStatus.CONFLICT, "Bản ghi gán đã bị thu hồi, không thể thao tác thêm");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AssignedPersonnelErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
