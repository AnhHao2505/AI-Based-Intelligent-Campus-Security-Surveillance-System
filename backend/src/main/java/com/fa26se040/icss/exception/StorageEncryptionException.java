package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * NFR-05: MinIO từ chối mã hoá phía server (SSE) khi ghi object -> upload thất bại, KHÔNG lưu dạng rõ.
 * Message không chứa secret (chỉ bucket, object, mã lỗi MinIO).
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class StorageEncryptionException extends RuntimeException {

    private final String errorCode;

    public StorageEncryptionException(String bucket, String object, String errorCode, Throwable cause) {
        super("[NFR-05] MinIO từ chối mã hoá phía server cho " + bucket + "/" + object
                + " (mã " + errorCode + "): không lưu dạng rõ, upload bị huỷ", cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
