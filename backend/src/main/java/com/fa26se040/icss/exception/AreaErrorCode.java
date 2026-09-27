package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AreaErrorCode {
    ERR_AREA_002("ERR_AREA_002", HttpStatus.NOT_FOUND, "Không tìm thấy khu vực"),
    ERR_AREA_003("ERR_AREA_003", HttpStatus.BAD_REQUEST, "Cấp độ khu vực không hợp lệ hoặc đã ngừng sử dụng"),
    ERR_AREA_004("ERR_AREA_004", HttpStatus.BAD_REQUEST, "Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự"),
    ERR_AREA_005("ERR_AREA_005", HttpStatus.BAD_REQUEST, "Tên khu vực bắt buộc, dài 3–100 ký tự"),
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
    ERR_AREA_016("ERR_AREA_016", HttpStatus.BAD_REQUEST, "Hình đa giác phải có ít nhất 3 đỉnh phân biệt (không trùng nhau)"),
    ERR_AREA_017("ERR_AREA_017", HttpStatus.BAD_REQUEST, "Khu vực đã ngừng hoạt động hoặc đã bị xoá"),
    ERR_AREA_018("ERR_AREA_018", HttpStatus.BAD_REQUEST, "Tên khu vực phải chứa ít nhất một chữ cái"),
    ERR_AREA_019("ERR_AREA_019", HttpStatus.BAD_REQUEST, "Tên khu vực chỉ được chứa chữ cái, số, khoảng trắng và các ký tự: - _ ( ) . , /"),
    ERR_AREA_020("ERR_AREA_020", HttpStatus.CONFLICT, "Tên khu vực đã tồn tại trong cùng tầng"),
    ERR_AREA_021("ERR_AREA_021", HttpStatus.BAD_REQUEST, "Thông tin tầng không hợp lệ hoặc không tìm thấy tầng tương ứng"),
    ERR_AREA_022("ERR_AREA_022", HttpStatus.BAD_REQUEST, "Chế độ sự kiện chỉ áp dụng cho khu vực Nội bộ (INTERNAL_CONFIDENTIAL) hoặc Yêu cầu liên hệ (CONFIDENTIAL_CONTACT_REQUIRED)"),
    ERR_AREA_023("ERR_AREA_023", HttpStatus.BAD_REQUEST, "Thời điểm kết thúc sự kiện phải ở trong tương lai"),
    ERR_AREA_024("ERR_AREA_024", HttpStatus.BAD_REQUEST, "Ghi chú bắt buộc 10–500 ký tự, nêu tên sự kiện hoặc đơn vị tổ chức"),
    ERR_AREA_025("ERR_AREA_025", HttpStatus.BAD_REQUEST, "Mã lý do không tồn tại hoặc đã ngừng sử dụng"),
    ERR_AREA_026("ERR_AREA_026", HttpStatus.BAD_REQUEST, "Mã lý do không phù hợp với loại thao tác sự kiện"),
    ERR_AREA_027("ERR_AREA_027", HttpStatus.BAD_REQUEST, "Thời gian mở sự kiện vượt quá giới hạn tối đa cho một phiên ({maxHours} giờ)"),
    ERR_AREA_028("ERR_AREA_028", HttpStatus.BAD_REQUEST, "Vượt quá ngân sách thời gian mở sự kiện của khu vực"),
    ERR_AREA_029("ERR_AREA_029", HttpStatus.BAD_REQUEST, "Không thể ngừng sử dụng mục lý do mặc định 'Khác'"),
    ERR_AREA_030("ERR_AREA_030", HttpStatus.CONFLICT, "Trạng thái sự kiện đã thay đổi: {status}. Vui lòng tải lại trang."),
    ERR_AREA_031("ERR_AREA_031", HttpStatus.BAD_REQUEST, "Thời lượng mở sự kiện tối thiểu là {minMinutes} phút.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AreaErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}

