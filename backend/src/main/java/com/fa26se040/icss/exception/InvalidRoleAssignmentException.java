package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InvalidRoleAssignmentException extends RuntimeException {
    public InvalidRoleAssignmentException(String message) {
        super(message);
    }
}
