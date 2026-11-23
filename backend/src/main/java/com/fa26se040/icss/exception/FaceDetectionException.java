package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Dịch vụ AI từ chối ảnh khuôn mặt tài khoản (ERR_FACE_005 / 006 / 007), 422. */
@Getter
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class FaceDetectionException extends RuntimeException {

    private final FaceErrorCode errorCode;

    public FaceDetectionException(FaceErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
