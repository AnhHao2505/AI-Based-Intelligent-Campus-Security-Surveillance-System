package com.fa26se040.icss.exception;

import lombok.Getter;

@Getter
public class AssignedPersonnelException extends RuntimeException {

    private final AssignedPersonnelErrorCode errorCode;

    public AssignedPersonnelException(AssignedPersonnelErrorCode errorCode) {
        super(errorCode.getMessageTemplate());
        this.errorCode = errorCode;
    }
}
