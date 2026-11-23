package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Dịch vụ AI không khả dụng / lỗi không do ảnh khi đăng ký khuôn mặt tài khoản: luôn ERR_FACE_008, 503. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AiServiceUnavailableException extends RuntimeException {

    public FaceErrorCode getErrorCode() {
        return FaceErrorCode.ERR_FACE_008;
    }

    public AiServiceUnavailableException(String message) {
        super(message);
    }
}
