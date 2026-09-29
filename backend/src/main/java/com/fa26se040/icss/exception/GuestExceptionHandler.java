package com.fa26se040.icss.exception;

import com.fa26se040.icss.dto.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handler riêng cho GuestException (không sửa GlobalExceptionHandler). Cùng format ApiResponse lỗi.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GuestExceptionHandler {

    @ExceptionHandler(GuestException.class)
    public ResponseEntity<ApiResponse<Object>> handleGuestException(GuestException ex) {
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(status.value(), ex.getErrorCode().getCode(), ex.getMessage());
        return new ResponseEntity<>(body, status);
    }
}
