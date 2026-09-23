package com.fa26se040.icss.exception;

import lombok.Getter;

@Getter
public class AccessControlException extends RuntimeException {

    private final AccessControlErrorCode errorCode;

    public AccessControlException(AccessControlErrorCode errorCode) {
        super(errorCode.getMessageTemplate());
        this.errorCode = errorCode;
    }

    public AccessControlException(AccessControlErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
