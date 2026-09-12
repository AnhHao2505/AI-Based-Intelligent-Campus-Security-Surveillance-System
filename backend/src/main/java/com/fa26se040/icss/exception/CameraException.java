package com.fa26se040.icss.exception;

import lombok.Getter;

@Getter
public class CameraException extends RuntimeException {

    private final CameraErrorCode errorCode;
    private final Object[] args;

    public CameraException(CameraErrorCode errorCode, Object... args) {
        super(formatMessage(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    private static String formatMessage(CameraErrorCode errorCode, Object[] args) {
        if (args != null && args.length > 0 && errorCode.getMessageTemplate().contains("{n}")) {
            return errorCode.getMessageTemplate().replace("{n}", String.valueOf(args[0]));
        }
        return errorCode.getMessageTemplate();
    }
}
