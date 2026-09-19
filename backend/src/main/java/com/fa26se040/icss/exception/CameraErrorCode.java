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
    ERR_MAP_002("ERR_MAP_002", HttpStatus.BAD_REQUEST, "Không thể gán camera đã ngừng hoạt động (DECOMMISSIONED)"),
    ERR_ROI_001("ERR_ROI_001", HttpStatus.BAD_REQUEST, "Số lượng polygon ROI phải từ 1 đến 10"),
    ERR_ROI_002("ERR_ROI_002", HttpStatus.BAD_REQUEST, "Mỗi polygon ROI phải có ít nhất 3 đỉnh"),
    ERR_ROI_003("ERR_ROI_003", HttpStatus.BAD_REQUEST, "Tọa độ đỉnh ROI phải trong khoảng [0.0, 1.0]"),
    ERR_ROI_004("ERR_ROI_004", HttpStatus.BAD_REQUEST, "Polygon ROI phải có ít nhất 3 đỉnh phân biệt"),
    ERR_ROI_005("ERR_ROI_005", HttpStatus.BAD_REQUEST, "Nhãn polygon ROI không được vượt quá 100 ký tự"),
    ERR_ROI_006("ERR_ROI_006", HttpStatus.BAD_REQUEST, "Khu vực liên kết (target_area_id) không tồn tại"),
    ERR_ROI_007("ERR_ROI_007", HttpStatus.BAD_REQUEST, "Loại cảnh báo (alert_rules) không hợp lệ"),
    ERR_SNAPSHOT_001("ERR_SNAPSHOT_001", HttpStatus.BAD_GATEWAY, "Không thể kết nối RTSP stream để chụp snapshot (AI-service)");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    CameraErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
