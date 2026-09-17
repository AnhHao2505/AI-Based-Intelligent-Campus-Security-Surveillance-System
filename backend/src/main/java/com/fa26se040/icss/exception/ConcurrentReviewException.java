package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ConcurrentReviewException extends RuntimeException {
    public ConcurrentReviewException(String message) {
        super(message);
    }
}
