package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Ảnh khuôn mặt tài khoản bị từ chối trước khi gọi dịch vụ AI: rỗng (ERR_FACE_002), quá USER_FACE_IMAGE_MAX_KB
 * (ERR_FACE_003), sai định dạng (ERR_FACE_004). 400 như trước. Kế thừa IllegalArgumentException để các chỗ đang bắt
 * IllegalArgumentException (FaceDataService.registerFace, test cũ) giữ nguyên hành vi; GlobalExceptionHandler có handler
 * riêng (gần hơn) nên response mang errorCode / code = ERR_FACE_xxx thay vì "Bad Request".
 */
@Getter
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class FaceImageException extends IllegalArgumentException {

    private final FaceErrorCode errorCode;

    public FaceImageException(FaceErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
