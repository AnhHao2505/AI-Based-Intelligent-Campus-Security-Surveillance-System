package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Mã lỗi module khách (V60). {n}, {name}, ... được nội suy từ config / dữ liệu, không viết cứng con số.
 */
@Getter
public enum GuestErrorCode {
    ERR_GUEST_001("ERR_GUEST_001", HttpStatus.NOT_FOUND, "Không tìm thấy lượt khách"),
    ERR_GUEST_002("ERR_GUEST_002", HttpStatus.FORBIDDEN, "Tài khoản không đủ điều kiện mời khách (cần tài khoản đang hoạt động, cấp truy cập từ {n} trở lên)"),
    ERR_GUEST_003("ERR_GUEST_003", HttpStatus.BAD_REQUEST, "Lượt khách phải có từ 1 đến {n} khách"),
    ERR_GUEST_004("ERR_GUEST_004", HttpStatus.BAD_REQUEST, "Họ tên khách phải từ 2 đến 100 ký tự"),
    ERR_GUEST_005("ERR_GUEST_005", HttpStatus.BAD_REQUEST, "Đơn vị của khách tối đa 200 ký tự"),
    ERR_GUEST_006("ERR_GUEST_006", HttpStatus.BAD_REQUEST, "Mục đích phải từ 10 đến 500 ký tự"),
    ERR_GUEST_007("ERR_GUEST_007", HttpStatus.BAD_REQUEST, "Phải chọn ít nhất 1 khu vực và các khu vực không được trùng nhau"),
    ERR_GUEST_008("ERR_GUEST_008", HttpStatus.BAD_REQUEST, "Khu vực {name} không tồn tại hoặc đã ngừng hoạt động"),
    ERR_GUEST_009("ERR_GUEST_009", HttpStatus.BAD_REQUEST, "Khu vực {name} không nhận khách (chỉ loại Bảo mật nội bộ hoặc Liên hệ trước)"),
    ERR_GUEST_010("ERR_GUEST_010", HttpStatus.BAD_REQUEST, "Thời điểm bắt đầu phải ở tương lai"),
    ERR_GUEST_011("ERR_GUEST_011", HttpStatus.BAD_REQUEST, "Thời điểm kết thúc phải sau thời điểm bắt đầu"),
    ERR_GUEST_012("ERR_GUEST_012", HttpStatus.BAD_REQUEST, "Lượt khách dài tối đa {n} giờ"),
    ERR_GUEST_013("ERR_GUEST_013", HttpStatus.BAD_REQUEST, "Chỉ được đặt lượt khách trước tối đa {n} ngày"),
    ERR_GUEST_014("ERR_GUEST_014", HttpStatus.FORBIDDEN, "Người mời không có quyền vào khu vực {name} trong suốt khung giờ của lượt khách (chỉ tính cấp truy cập hoặc chỉ định nhân sự)"),
    ERR_GUEST_015("ERR_GUEST_015", HttpStatus.BAD_REQUEST, "Thiếu version của lượt khách"),
    ERR_GUEST_016("ERR_GUEST_016", HttpStatus.CONFLICT, "Lượt khách đã được người khác cập nhật, vui lòng tải lại"),
    ERR_GUEST_017("ERR_GUEST_017", HttpStatus.CONFLICT, "Lượt khách đang ở trạng thái {status}, không thể {action}"),
    ERR_GUEST_018("ERR_GUEST_018", HttpStatus.CONFLICT, "Lượt khách đã kết thúc, không thể {action}"),
    ERR_GUEST_019("ERR_GUEST_019", HttpStatus.BAD_REQUEST, "Lý do phải từ 10 đến 500 ký tự"),
    ERR_GUEST_020("ERR_GUEST_020", HttpStatus.BAD_REQUEST, "Thiếu dữ liệu bắt buộc: {field}"),
    ERR_GUEST_021("ERR_GUEST_021", HttpStatus.CONFLICT, "Lượt khách không còn thoả điều kiện để duyệt: {reason}"),
    ERR_GUEST_022("ERR_GUEST_022", HttpStatus.FORBIDDEN, "Không được tự duyệt, từ chối hoặc thu hồi lượt khách mà bạn là người mời"),
    ERR_GUEST_023("ERR_GUEST_023", HttpStatus.CONFLICT, "Đã tới giờ bắt đầu, không thể duyệt lượt khách"),
    ERR_GUEST_024("ERR_GUEST_024", HttpStatus.BAD_REQUEST, "Quyết định phải là APPROVED hoặc REJECTED"),
    ERR_GUEST_025("ERR_GUEST_025", HttpStatus.BAD_REQUEST, "Trường {field} không có trong mẫu lượt khách (không nhận CCCD, SĐT, địa chỉ, email)"),
    ERR_GUEST_030("ERR_GUEST_030", HttpStatus.CONFLICT, "Chỉ gắn ảnh cho khách của lượt đã duyệt và chưa kết thúc"),
    ERR_GUEST_031("ERR_GUEST_031", HttpStatus.BAD_REQUEST, "Phải xác nhận khách đã đồng ý theo thông báo phiên bản {version}"),
    ERR_GUEST_032("ERR_GUEST_032", HttpStatus.NOT_FOUND, "Không tìm thấy khách trong lượt này"),
    ERR_GUEST_033("ERR_GUEST_033", HttpStatus.BAD_REQUEST, "Ảnh phải là JPG hoặc PNG, tối đa {n} KB"),
    ERR_GUEST_034("ERR_GUEST_034", HttpStatus.BAD_REQUEST, "Ảnh phải có đúng 1 khuôn mặt (phát hiện {n})"),
    ERR_GUEST_035("ERR_GUEST_035", HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ AI không trích xuất được khuôn mặt, vui lòng thử lại sau"),
    ERR_GUEST_036("ERR_GUEST_036", HttpStatus.SERVICE_UNAVAILABLE, "Kho lưu ảnh khách tạm thời không dùng được, vui lòng thử lại sau"),
    ERR_GUEST_037("ERR_GUEST_037", HttpStatus.NOT_FOUND, "Khách chưa có ảnh hoặc ảnh đã bị xoá");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    GuestErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
