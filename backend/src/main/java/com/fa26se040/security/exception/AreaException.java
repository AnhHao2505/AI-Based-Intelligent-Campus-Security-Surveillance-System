package com.fa26se040.security.exception;

import lombok.Getter;

@Getter
public class AreaException extends RuntimeException {

    private final AreaErrorCode errorCode;
    private final Object[] args;

    public AreaException(AreaErrorCode errorCode, Object... args) {
        super(formatMessage(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    private static String formatMessage(AreaErrorCode errorCode, Object[] args) {
        if (args != null && args.length > 0 && errorCode.getMessageTemplate().contains("{n}")) {
            return errorCode.getMessageTemplate().replace("{n}", String.valueOf(args[0]));
        }
        return errorCode.getMessageTemplate();
    }
}
