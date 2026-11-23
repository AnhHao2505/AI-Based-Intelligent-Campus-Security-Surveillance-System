package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * face-gate 5: khuôn mặt trùng một tài khoản hoặc khách đã có, ở các luồng ADMIN (tạo tài khoản, import ZIP, kích hoạt
 * lại, khôi phục). 409, errorCode {@link #CODE}; câu lỗi nêu chi tiết người trùng (chỉ ADMIN thấy).
 * Luồng host (ảnh khách) dùng {@code GuestErrorCode.ERR_GUEST_038 / ERR_GUEST_041}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class FaceDuplicateException extends RuntimeException {

    public static final String CODE = "ERR_FACE_001";

    public FaceDuplicateException(String message) {
        super(message);
    }
}
