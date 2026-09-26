package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fa26se040.icss.dto.common.ApiResponse;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxRecordsExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxRecordsExceeded(MaxRecordsExceededException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "PAYLOAD_TOO_LARGE",
                "File nạp vào vượt quá dung lượng tối đa cho phép (500MB)."
        );
        return new ResponseEntity<>(body, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnauthorized(UnauthorizedException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AreaException.class)
    public ResponseEntity<ApiResponse<Object>> handleAreaException(AreaException ex) {
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : ex.getErrorCode().getMessageTemplate();
        if (ex.getArgs() != null && ex.getArgs().length > 0 && message.contains("{n}")) {
            message = message.replace("{n}", String.valueOf(ex.getArgs()[0]));
        }
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                message
        );
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(CameraException.class)
    public ResponseEntity<ApiResponse<Object>> handleCameraException(CameraException ex) {
        String message = ex.getErrorCode().getMessageTemplate();
        if (ex.getArgs() != null && ex.getArgs().length > 0 && message.contains("{n}")) {
            message = message.replace("{n}", String.valueOf(ex.getArgs()[0]));
        }
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                message
        );
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(AssignedPersonnelException.class)
    public ResponseEntity<ApiResponse<Object>> handleAssignedPersonnelException(AssignedPersonnelException ex) {
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getMessageTemplate()
        );
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(AccessControlException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessControlException(AccessControlException ex) {
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                ex.getMessage() != null ? ex.getMessage() : ex.getErrorCode().getMessageTemplate()
        );
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLockingFailure(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.CONFLICT.value(),
                AccessControlErrorCode.ERR_AC_003.getCode(),
                AccessControlErrorCode.ERR_AC_003.getMessageTemplate()
        );
        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied: " + ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateResource(DuplicateResourceException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ConcurrentReviewException.class)
    public ResponseEntity<ApiResponse<Object>> handleConcurrentReview(ConcurrentReviewException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(FaceDetectionException.class)
    public ResponseEntity<ApiResponse<Object>> handleFaceDetection(FaceDetectionException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Object>> handleAiServiceUnavailable(AiServiceUnavailableException ex) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(InvalidRoleAssignmentException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidRoleAssignment(InvalidRoleAssignmentException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        String msg = (ex.getMessage() + " " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
        if (msg.contains("ux_areas_floor_name_active")) {
            return handleAreaException(new AreaException(AreaErrorCode.ERR_AREA_020));
        }
        return buildResponse(HttpStatus.CONFLICT, "Dữ liệu bị trùng lặp hoặc vi phạm ràng buộc toàn vẹn");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(RuntimeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        Object value = ex.getValue();
        String message = String.format("Giá trị '%s' không hợp lệ cho tham số '%s'", value, name);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            errors.put(error.getField(), error.getDefaultMessage())
        );
        
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Dữ liệu đầu vào không hợp lệ",
                errors
        );
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneral(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred: " + ex.getMessage());
    }

    private ResponseEntity<ApiResponse<Object>> buildResponse(HttpStatus status, String message) {
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                status.getReasonPhrase(),
                message
        );
        return new ResponseEntity<>(body, status);
    }
}

