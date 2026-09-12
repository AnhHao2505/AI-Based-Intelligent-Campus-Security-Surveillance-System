package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class MaxRecordsExceededException extends RuntimeException {
    public MaxRecordsExceededException(String message) {
        super(message);
    }
}
