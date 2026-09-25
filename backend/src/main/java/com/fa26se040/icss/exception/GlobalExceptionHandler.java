package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxRecordsExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxRecordsExceeded(MaxRecordsExceededException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        body.put("error", HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase());
        body.put("code", "PAYLOAD_TOO_LARGE");
        body.put("message", "File nạp vào vượt quá dung lượng tối đa cho phép (500MB).");
        return new ResponseEntity<>(body, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AreaException.class)
    public ResponseEntity<Map<String, Object>> handleAreaException(AreaException ex) {
        String message = ex.getErrorCode().getMessageTemplate();
        if (ex.getArgs() != null && ex.getArgs().length > 0 && message.contains("{n}")) {
            message = message.replace("{n}", String.valueOf(ex.getArgs()[0]));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.getErrorCode().getHttpStatus().value());
        body.put("error", ex.getErrorCode().getHttpStatus().getReasonPhrase());
        body.put("code", ex.getErrorCode().getCode());
        body.put("message", message);
        return new ResponseEntity<>(body, ex.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(CameraException.class)
    public ResponseEntity<Map<String, Object>> handleCameraException(CameraException ex) {
        String message = ex.getErrorCode().getMessageTemplate();
        if (ex.getArgs() != null && ex.getArgs().length > 0 && message.contains("{n}")) {
            message = message.replace("{n}", String.valueOf(ex.getArgs()[0]));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.getErrorCode().getHttpStatus().value());
        body.put("error", ex.getErrorCode().getHttpStatus().getReasonPhrase());
        body.put("code", ex.getErrorCode().getCode());
        body.put("message", message);
        return new ResponseEntity<>(body, ex.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(AssignedPersonnelException.class)
    public ResponseEntity<Map<String, Object>> handleAssignedPersonnelException(AssignedPersonnelException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.getErrorCode().getHttpStatus().value());
        body.put("error", ex.getErrorCode().getHttpStatus().getReasonPhrase());
        body.put("code", ex.getErrorCode().getCode());
        body.put("message", ex.getErrorCode().getMessageTemplate());
        return new ResponseEntity<>(body, ex.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(AccessControlException.class)
    public ResponseEntity<Map<String, Object>> handleAccessControlException(AccessControlException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.getErrorCode().getHttpStatus().value());
        body.put("error", ex.getErrorCode().getHttpStatus().getReasonPhrase());
        body.put("code", ex.getErrorCode().getCode());
        body.put("message", ex.getMessage() != null ? ex.getMessage() : ex.getErrorCode().getMessageTemplate());
        return new ResponseEntity<>(body, ex.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockingFailure(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        body.put("code", AccessControlErrorCode.ERR_AC_003.getCode());
        body.put("message", AccessControlErrorCode.ERR_AC_003.getMessageTemplate());
        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied: " + ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateResource(DuplicateResourceException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ConcurrentReviewException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentReview(ConcurrentReviewException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(FaceDetectionException.class)
    public ResponseEntity<Map<String, Object>> handleFaceDetection(FaceDetectionException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAiServiceUnavailable(AiServiceUnavailableException ex) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(InvalidRoleAssignmentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRoleAssignment(InvalidRoleAssignmentException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        String msg = (ex.getMessage() + " " + (ex.getRootCause() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
        if (msg.contains("ux_areas_floor_name_active")) {
            return handleAreaException(new AreaException(AreaErrorCode.ERR_AREA_020));
        }
        return buildResponse(HttpStatus.CONFLICT, "Dữ liệu bị trùng lặp hoặc vi phạm ràng buộc toàn vẹn");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        Object value = ex.getValue();
        String message = String.format("Giá trị '%s' không hợp lệ cho tham số '%s'", value, name);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            errors.put(error.getField(), error.getDefaultMessage())
        );
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation Error");
        body.put("details", errors);
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred: " + ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }
}
