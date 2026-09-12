package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum CameraErrorCode {
    ERR_CAM_001("ERR_CAM_001", HttpStatus.BAD_REQUEST, "Tên camera để trống hoặc không hợp lệ"),
    ERR_CAM_002("ERR_CAM_002", HttpStatus.NOT_FOUND, "Không tìm thấy camera"),
    ERR_CAM_003("ERR_CAM_003", HttpStatus.CONFLICT, "Mã camera đã tồn tại trên hệ thống"),
    ERR_STREAM_001("ERR_STREAM_001", HttpStatus.BAD_REQUEST, "Thông số cấu hình luồng RTSP không hợp lệ"),
    ERR_STREAM_002("ERR_STREAM_002", HttpStatus.BAD_GATEWAY, "Lỗi kết nối tới MediaMTX Control API"),
    ERR_MAP_001("ERR_MAP_001", HttpStatus.NOT_FOUND, "Không tìm thấy khu vực"),
    ERR_MAP_002("ERR_MAP_002", HttpStatus.BAD_REQUEST, "Không thể gán camera đã ngừng hoạt động (DECOMMISSIONED)");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    CameraErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
