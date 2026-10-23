package com.fa26se040.icss.enums;

import lombok.Getter;

@Getter
public enum RtspConnectionErrorCode {
    ERR_CONN_HOST_EMPTY("ERR_CONN_HOST_EMPTY", "Địa chỉ IP/Host của camera không được để trống"),
    ERR_CONN_TIMEOUT("ERR_CONN_TIMEOUT", "Quá thời gian chờ phản hồi từ camera (Timeout sau {timeout}ms). Vui lòng kiểm tra IP và nguồn điện camera."),
    ERR_CONN_PORT_REFUSED("ERR_CONN_PORT_REFUSED", "Không thể kết nối đến cổng {port}. Vui lòng kiểm tra cổng RTSP trên camera."),
    ERR_CONN_HOST_UNREACHABLE("ERR_CONN_HOST_UNREACHABLE", "Không tìm thấy thiết bị tại địa chỉ IP/Host {host}."),
    ERR_CONN_INVALID_PROTOCOL("ERR_CONN_INVALID_PROTOCOL", "Cổng {port} đang mở nhưng thiết bị không phản hồi đúng giao thức RTSP."),
    ERR_CONN_RTSP_ERROR("ERR_CONN_RTSP_ERROR", "Phản hồi RTSP: {statusCode} {statusText}."),
    ERR_CONN_UNKNOWN("ERR_CONN_UNKNOWN", "Không thể thiết lập kết nối tới camera: {detail}.");

    private final String code;
    private final String template;

    RtspConnectionErrorCode(String code, String template) {
        this.code = code;
        this.template = template;
    }

    public String format(Object... args) {
        String msg = this.template;
        if (args == null || args.length == 0) {
            return msg;
        }
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length && args[i] != null && args[i + 1] != null) {
                msg = msg.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
            }
        }
        return msg;
    }
}
