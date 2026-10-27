package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AccessControlErrorCode {
    ERR_AC_003("ERR_AC_003", HttpStatus.CONFLICT, "Dữ liệu cấu hình đã bị thay đổi bởi người khác. Vui lòng tải lại và thử lại."),
    ERR_AC_004("ERR_AC_004", HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình mặc định cho loại khu vực này"),
    // V83 (D2): bảng cấp theo loại chỉ để xem; PUT/PATCH /api/access-control/level-presets/{areaLevel} luôn trả lỗi này
    ERR_AC_005("ERR_AC_005", HttpStatus.BAD_REQUEST, "Cấp khu vực cố định theo loại (Công khai: cấp 1, Bảo mật: cấp 2, Tuyệt mật: cấp 3), không sửa được"),
    // BR-RQ-06: người duyệt đơn truy cập phải khác người tạo đơn
    ERR_AC_006("ERR_AC_006", HttpStatus.FORBIDDEN, "Người tạo đơn không được tự duyệt hoặc tự từ chối đơn truy cập của chính mình"),
    // BR-RQ-46: chỉ chuyển sang Hoàn thành khi khung giờ của đơn đã bắt đầu; trước đó dùng Huỷ
    ERR_AC_007("ERR_AC_007", HttpStatus.BAD_REQUEST, "Đơn truy cập chưa đến giờ bắt đầu ({startTime}) nên chưa thể chuyển sang Hoàn thành"),
    // BR-RQ-47: FM chỉ huỷ đơn đã duyệt khi chưa tới giờ bắt đầu; đã bắt đầu thì dùng Hoàn thành
    ERR_AC_008("ERR_AC_008", HttpStatus.BAD_REQUEST, "Đơn truy cập đã bắt đầu ({startTime}) nên không thể huỷ, hãy dùng Hoàn thành"),
    // BR-AL-29: không đổi cấp truy cập của tài khoản đã vô hiệu hoá (đổi lẻ và đổi nhiều người)
    ERR_AC_009("ERR_AC_009", HttpStatus.CONFLICT, "Tài khoản {userCode} đã bị vô hiệu hoá nên không thể đổi cấp truy cập"),
    // Lịch sử ra vào: khoảng thời gian lọc phải có from <= to
    ERR_AC_010("ERR_AC_010", HttpStatus.BAD_REQUEST, "Thời điểm bắt đầu ({from}) phải trước hoặc bằng thời điểm kết thúc ({to})"),
    // BR-AL-30 (V83): không đổi cấp (nâng hay hạ) khi người dùng là người tạo hoặc thành viên của đơn PENDING
    ERR_AC_011("ERR_AC_011", HttpStatus.CONFLICT, "Người dùng {userCode} – {fullName} đang có {count} yêu cầu chờ duyệt ({areas}). Hãy duyệt hoặc từ chối các yêu cầu này trước khi đổi cấp."),
    // D-1: tới giờ bắt đầu (now >= startTime) thì không duyệt / từ chối đơn PENDING nữa; job chuyển sang EXPIRED
    ERR_AC_012("ERR_AC_012", HttpStatus.CONFLICT, "Đã tới giờ bắt đầu, không thể duyệt hoặc từ chối yêu cầu. Hệ thống sẽ chuyển yêu cầu sang Hết hạn."),
    // BR-AL-32: FM chỉ đổi cấp truy cập của NORMAL_USER và GUARD (đổi lẻ và đổi nhiều người)
    ERR_AC_013("ERR_AC_013", HttpStatus.FORBIDDEN, "Chỉ được đổi cấp truy cập của người dùng thường và bảo vệ; không đổi cấp của quản trị viên hoặc quản lý cơ sở vật chất ({userCode})");

    private final String code;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    AccessControlErrorCode(String code, HttpStatus httpStatus, String messageTemplate) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }
}
