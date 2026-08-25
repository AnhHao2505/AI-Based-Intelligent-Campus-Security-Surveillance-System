package com.fa26se040.security.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AreaErrorCode {
    ERR_AREA_001("ERR_AREA_001", HttpStatus.CONFLICT, "Mã khu vực đã tồn tại"),
    ERR_AREA_002("ERR_AREA_002", HttpStatus.NOT_FOUND, "Không tìm thấy khu vực"),
    ERR_AREA_003("ERR_AREA_003", HttpStatus.BAD_REQUEST, "Cấp độ khu vực không hợp lệ hoặc đã ngừng sử dụng"),
    ERR_AREA_004("ERR_AREA_004", HttpStatus.BAD_REQUEST, "Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự"),
    ERR_AREA_005("ERR_AREA_005", HttpStatus.BAD_REQUEST, "Tên khu vực bắt buộc, tối đa 150 ký tự"),
    ERR_AREA_006("ERR_AREA_006", HttpStatus.BAD_REQUEST, "Toạ độ bản đồ phải có đủ cả X và Y"),
    ERR_AREA_007("ERR_AREA_007", HttpStatus.BAD_REQUEST, "Không được thay đổi mã khu vực sau khi tạo"),
    ERR_AREA_008("ERR_AREA_008", HttpStatus.BAD_REQUEST, "Hạ cấp độ khu vực bắt buộc nhập lý do"),
    ERR_AREA_009("ERR_AREA_009", HttpStatus.CONFLICT, "Không thể ngừng: còn {n} camera đang gán"),
    ERR_AREA_010("ERR_AREA_010", HttpStatus.CONFLICT, "Không thể ngừng: còn {n} quyền truy cập");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AreaErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
