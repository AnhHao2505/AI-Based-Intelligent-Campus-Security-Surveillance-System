package com.fa26se040.icss.exception;

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
    ERR_AREA_010("ERR_AREA_010", HttpStatus.CONFLICT, "Không thể ngừng: còn {n} quyền truy cập"),
    ERR_AREA_011("ERR_AREA_011", HttpStatus.BAD_REQUEST, "Hình đa giác phải có ít nhất 3 đỉnh"),
    ERR_AREA_012("ERR_AREA_012", HttpStatus.BAD_REQUEST, "Toạ độ đỉnh đa giác phải nằm trong khoảng [0, 1]"),
    ERR_AREA_013("ERR_AREA_013", HttpStatus.CONFLICT, "Toạ độ đa giác bị chồng lấn với khu vực khác cùng tầng"),
    ERR_AREA_014("ERR_AREA_014", HttpStatus.BAD_REQUEST, "Không thể thay đổi toà nhà hoặc tầng khi khu vực đang có toạ độ đa giác. Vui lòng xoá đa giác trước"),
    ERR_AREA_015("ERR_AREA_015", HttpStatus.BAD_REQUEST, "Khu vực phải có thông tin toà nhà và tầng trước khi gán toạ độ đa giác"),
    ERR_AREA_016("ERR_AREA_016", HttpStatus.BAD_REQUEST, "Hình đa giác phải có ít nhất 3 đỉnh phân biệt (không trùng nhau)");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AreaErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}

