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
    ERR_AREA_009("ERR_AREA_009", HttpStatus.CONFLICT, "Không thể vô hiệu hoá: còn {n} camera đang gán. Gỡ camera khỏi khu vực trước"),
    ERR_AREA_010("ERR_AREA_010", HttpStatus.CONFLICT, "Không thể ngừng: còn {n} quyền truy cập"),
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
    ERR_AREA_031("ERR_AREA_031", HttpStatus.BAD_REQUEST, "Thời lượng mở sự kiện tối thiểu là {minMinutes} phút."),
    ERR_AREA_032("ERR_AREA_032", HttpStatus.BAD_REQUEST, "Thời điểm bắt đầu lịch sự kiện phải ở trong tương lai"),
    ERR_AREA_033("ERR_AREA_033", HttpStatus.BAD_REQUEST, "Thời điểm bắt đầu vượt quá thời gian đặt trước tối đa ({maxLeadDays} ngày)"),
    ERR_AREA_034("ERR_AREA_034", HttpStatus.BAD_REQUEST, "Thời điểm kết thúc lịch sự kiện phải sau thời điểm bắt đầu"),
    ERR_AREA_035("ERR_AREA_035", HttpStatus.BAD_REQUEST, "Thời lượng lịch sự kiện tối thiểu là {minMinutes} phút"),
    ERR_AREA_036("ERR_AREA_036", HttpStatus.BAD_REQUEST, "Thời lượng lịch sự kiện vượt quá giới hạn tối đa cho một phiên ({maxHours} giờ)"),
    ERR_AREA_037("ERR_AREA_037", HttpStatus.CONFLICT, "Thời gian lịch sự kiện bị trùng hoặc chồng lấn với lịch sự kiện khác đã đặt"),
    ERR_AREA_038("ERR_AREA_038", HttpStatus.CONFLICT, "Thời gian lịch sự kiện bị trùng hoặc chồng lấn với phiên sự kiện đang hoạt động"),
    ERR_AREA_039("ERR_AREA_039", HttpStatus.BAD_REQUEST, "Số lượng lịch sự kiện chờ diễn ra của khu vực đã đạt tối đa ({maxSchedules})"),
    ERR_AREA_040("ERR_AREA_040", HttpStatus.CONFLICT, "Chỉ có thể sửa hoặc huỷ lịch sự kiện ở trạng thái chờ diễn ra (SCHEDULED)"),
    ERR_AREA_041("ERR_AREA_041", HttpStatus.CONFLICT, "Thời gian mở sự kiện bị trùng hoặc chồng lấn với lịch sự kiện đã đặt trước"),
    ERR_AREA_042("ERR_AREA_042", HttpStatus.CONFLICT, "Khu vực còn {count} lịch sự kiện chưa diễn ra, huỷ lịch trước"),
    ERR_AREA_043("ERR_AREA_043", HttpStatus.NOT_FOUND, "Không tìm thấy lịch sự kiện"),
    // Step 5b — đổi loại khu vực, version khu vực, ý định chế độ sự kiện
    ERR_AREA_044("ERR_AREA_044", HttpStatus.BAD_REQUEST, "Thiếu version của khu vực, vui lòng tải lại trang"),
    ERR_AREA_045("ERR_AREA_045", HttpStatus.CONFLICT, "Khu vực đã được người khác cập nhật, vui lòng tải lại"),
    ERR_AREA_046("ERR_AREA_046", HttpStatus.BAD_REQUEST, "Trường enabled không còn được hỗ trợ, hãy gửi action (ENABLE, ADJUST, DISABLE)"),
    ERR_AREA_047("ERR_AREA_047", HttpStatus.BAD_REQUEST, "Thiếu action của thao tác chế độ sự kiện (ENABLE, ADJUST, DISABLE)"),
    ERR_AREA_048("ERR_AREA_048", HttpStatus.CONFLICT, "Khu vực đang mở chế độ sự kiện, không thể đổi sang loại {newType}. Tắt sự kiện trước khi đổi loại"),
    ERR_AREA_049("ERR_AREA_049", HttpStatus.CONFLICT, "Khu vực còn {count} quyền gán nhân sự còn hiệu lực, thu hồi trước khi chuyển sang Công khai"),
    ERR_AREA_050("ERR_AREA_050", HttpStatus.BAD_REQUEST, "Lý do phải có từ 10 đến 500 ký tự"),
    // Step 6 — vô hiệu hoá khu vực (BR-AD)
    ERR_AREA_051("ERR_AREA_051", HttpStatus.CONFLICT, "Khu vực còn {count} sự cố chưa xử lý xong (mới hoặc đang xử lý). Xử lý sự cố trước khi vô hiệu hoá"),
    ERR_AREA_052("ERR_AREA_052", HttpStatus.CONFLICT, "Khu vực đang mở chế độ sự kiện. Tắt chế độ sự kiện trước khi vô hiệu hoá"),
    ERR_AREA_053("ERR_AREA_053", HttpStatus.CONFLICT, "Khu vực đã bị vô hiệu hoá"),
    ERR_AREA_056("ERR_AREA_056", HttpStatus.BAD_REQUEST, "Cờ yêu cầu chỉ định (explicitAuthorizationRequired) được suy ra từ loại khu vực ({areaLevel}) và không được thay đổi độc lập");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AreaErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}

