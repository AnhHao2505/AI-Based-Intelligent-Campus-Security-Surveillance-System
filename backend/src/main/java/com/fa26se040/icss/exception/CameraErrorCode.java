package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum CameraErrorCode {
    ERR_CAM_002("ERR_CAM_002", HttpStatus.NOT_FOUND, "Không tìm thấy camera"),
    ERR_CAM_003("ERR_CAM_003", HttpStatus.CONFLICT, "Mã camera đã tồn tại trên hệ thống"),
    ERR_CAM_004("ERR_CAM_004", HttpStatus.BAD_REQUEST, "Camera phải được gán vào một khu vực"),
    ERR_CAM_005("ERR_CAM_005", HttpStatus.BAD_REQUEST, "Cấp độ bảo mật của khu vực không khớp với loại camera đã chọn"),
    ERR_CAM_006("ERR_CAM_006", HttpStatus.BAD_REQUEST, "Camera hành lang bắt buộc phải có thông tin tòa nhà và tầng trên bản đồ"),
    ERR_CAM_007("ERR_CAM_007", HttpStatus.BAD_REQUEST, "Khi thay đổi khu vực hoặc loại vị trí camera, bắt buộc phải cập nhật lại vị trí ghim trên bản đồ"),
    ERR_STREAM_001("ERR_STREAM_001", HttpStatus.BAD_REQUEST, "Thông số cấu hình luồng RTSP không hợp lệ: Vui lòng kiểm tra lại host, cổng mạng và đường dẫn luồng"),
    ERR_STREAM_002("ERR_STREAM_002", HttpStatus.BAD_GATEWAY, "Lỗi kết nối tới MediaMTX Control API"),
    ERR_MAP_002("ERR_MAP_002", HttpStatus.BAD_REQUEST, "Không thể gán camera đã ngừng hoạt động (INACTIVE)"),
    ERR_MAP_003("ERR_MAP_003", HttpStatus.BAD_REQUEST, "Tọa độ bản đồ không hợp lệ"),
    ERR_MAP_004("ERR_MAP_004", HttpStatus.BAD_REQUEST, "Vị trí tòa nhà hoặc tầng trên bản đồ không khớp với khu vực của camera"),
    ERR_ROI_001("ERR_ROI_001", HttpStatus.BAD_REQUEST, "Số lượng polygon ROI không được vượt quá 10"),
    ERR_ROI_002("ERR_ROI_002", HttpStatus.BAD_REQUEST, "Mỗi polygon ROI phải có ít nhất 3 đỉnh"),
    ERR_ROI_003("ERR_ROI_003", HttpStatus.BAD_REQUEST, "Tọa độ đỉnh ROI phải trong khoảng [0.0, 1.0]"),
    ERR_ROI_004("ERR_ROI_004", HttpStatus.BAD_REQUEST, "Polygon ROI phải có ít nhất 3 đỉnh phân biệt"),
    ERR_ROI_005("ERR_ROI_005", HttpStatus.BAD_REQUEST, "Nhãn polygon ROI không được vượt quá 100 ký tự"),
    ERR_ROI_007("ERR_ROI_007", HttpStatus.BAD_REQUEST, "Camera phải có ít nhất 1 vùng giám sát ROI"),
    ERR_ROI_008("ERR_ROI_008", HttpStatus.BAD_REQUEST, "Diện tích vùng phát hiện xâm nhập phải đạt tối thiểu 5% khung hình"),
    ERR_STREAM_003("ERR_STREAM_003", HttpStatus.BAD_REQUEST, "Camera đang ở trạng thái ngừng hoạt động (INACTIVE), không thể thao tác luồng stream"),
    ERR_STREAM_004("ERR_STREAM_004", HttpStatus.BAD_REQUEST, "Camera đang phát trực tiếp (ONLINE), vui lòng ngắt stream trước khi thay đổi cấu hình kết nối"),
    ERR_STREAM_005("ERR_STREAM_005", HttpStatus.CONFLICT, "Địa chỉ luồng RTSP này đang được sử dụng bởi camera khác"),
    ERR_RECORDING_001("ERR_RECORDING_001", HttpStatus.BAD_REQUEST, "Thông tin bản ghi video không hợp lệ hoặc thiếu đường dẫn lưu trữ"),
    ERR_RECORDING_002("ERR_RECORDING_002", HttpStatus.CONFLICT, "Chuyển trạng thái bản ghi video không hợp lệ"),
    ERR_EDGE_001("ERR_EDGE_001", HttpStatus.BAD_GATEWAY, "Không thể gửi lệnh điều khiển tới trạm Edge qua MQTT Broker"),
    ERR_EDGE_002("ERR_EDGE_002", HttpStatus.GATEWAY_TIMEOUT, "Quá thời gian chờ phản hồi từ trạm Edge AI (MQTT RPC Timeout)");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    CameraErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
