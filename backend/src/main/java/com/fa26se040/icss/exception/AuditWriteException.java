package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class AuditWriteException extends RuntimeException {
    private final String code = "ERR_AUDIT_001";

    public AuditWriteException(String message) {
        super(message);
    }

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getCode() {
        return code;
    }
}
