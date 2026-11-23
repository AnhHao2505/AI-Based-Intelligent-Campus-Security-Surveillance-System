package com.fa26se040.icss.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * Mã lỗi ảnh khuôn mặt TÀI KHOẢN (tạo tài khoản kèm ảnh, nạp theo lô, cả chế độ Cloud-Edge MQTT). Câu báo do nơi ném
 * lỗi đặt (nội suy giá trị cấu hình như USER_FACE_IMAGE_MAX_KB), enum chỉ giữ mã và HTTP status.
 * Ảnh khách dùng {@link GuestErrorCode} (ERR_GUEST_033 / 034 / 038 / 041...).
 */
@Getter
public enum FaceErrorCode {
    /** Khuôn mặt trùng một tài khoản hoặc khách đã có ({@link FaceDuplicateException}). */
    ERR_FACE_001("ERR_FACE_001", HttpStatus.CONFLICT),
    /** Không có ảnh / file ảnh rỗng (nạp theo lô: thiếu ảnh của mã trong images/). */
    ERR_FACE_002("ERR_FACE_002", HttpStatus.BAD_REQUEST),
    /** Ảnh vượt USER_FACE_IMAGE_MAX_KB. */
    ERR_FACE_003("ERR_FACE_003", HttpStatus.BAD_REQUEST),
    /** Không phải JPG / PNG. */
    ERR_FACE_004("ERR_FACE_004", HttpStatus.BAD_REQUEST),
    /** Ảnh không có khuôn mặt. */
    ERR_FACE_005("ERR_FACE_005", HttpStatus.UNPROCESSABLE_ENTITY),
    /** Ảnh có nhiều hơn 1 khuôn mặt. */
    ERR_FACE_006("ERR_FACE_006", HttpStatus.UNPROCESSABLE_ENTITY),
    /** Dịch vụ AI từ chối ảnh vì lý do khác số khuôn mặt (không đọc / giải mã được ảnh), hoặc báo không trích xuất được. */
    ERR_FACE_007("ERR_FACE_007", HttpStatus.UNPROCESSABLE_ENTITY),
    /** Dịch vụ AI không khả dụng hoặc lỗi không do ảnh (mất kết nối, quá hạn RPC edge, 5xx, kết quả thiếu). */
    ERR_FACE_008("ERR_FACE_008", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final HttpStatus httpStatus;

    FaceErrorCode(String code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** Mã của lỗi ảnh khuôn mặt tài khoản, rỗng nếu lỗi không thuộc nhóm này (dùng cho kết quả từng dòng nạp theo lô). */
    public static Optional<FaceErrorCode> of(Throwable e) {
        if (e instanceof FaceImageException fie) {
            return Optional.of(fie.getErrorCode());
        }
        if (e instanceof FaceDetectionException fde) {
            return Optional.of(fde.getErrorCode());
        }
        if (e instanceof AiServiceUnavailableException ase) {
            return Optional.of(ase.getErrorCode());
        }
        if (e instanceof FaceDuplicateException) {
            return Optional.of(ERR_FACE_001);
        }
        return Optional.empty();
    }
}
