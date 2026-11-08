package com.fa26se040.icss.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fa26se040.icss.dto.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
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
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                message
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
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
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(AssignedPersonnelException.class)
    public ResponseEntity<ApiResponse<Object>> handleAssignedPersonnelException(AssignedPersonnelException ex) {
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getMessageTemplate()
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(AccessControlException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessControlException(AccessControlException ex) {
        HttpStatus status = ex.getErrorCode().getHttpStatus();
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                ex.getErrorCode().getCode(),
                ex.getMessage() != null ? ex.getMessage() : ex.getErrorCode().getMessageTemplate()
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLockingFailure(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.CONFLICT.value(),
                AccessControlErrorCode.ERR_AC_003.getCode(),
                AccessControlErrorCode.ERR_AC_003.getMessageTemplate()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
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
        if (msg.contains("ux_area_event_sessions_active")) {
            return handleAreaException(new AreaException(AreaErrorCode.ERR_AREA_030, "Trạng thái sự kiện đã thay đổi do có thao tác đồng thời. Vui lòng tải lại trang."));
        }
        return buildResponse(HttpStatus.CONFLICT, "Dữ liệu bị trùng lặp hoặc vi phạm ràng buộc toàn vẹn");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(RuntimeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Step 5b (BR-EV-A1): body JSON sai cú pháp, sai kiểu (vd. openUntil, version) hoặc enum lạ (vd. action) -> 400,
     * không rơi vào handleGeneral (500).
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String message = "Dữ liệu gửi lên không đúng định dạng";
        Throwable cause = ex.getCause();
        if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            message = String.format("Giá trị '%s' không hợp lệ cho trường '%s'", ife.getValue(), field);
        }
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        Object value = ex.getValue();
        String message = String.format("Giá trị '%s' không hợp lệ cho tham số '%s'", value, name);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    /** B-06: thiếu query param bắt buộc (vd. actionType của /api/reason-catalogs/active) -> 400, không rơi vào handleGeneral (500). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingRequestParameter(MissingServletRequestParameterException ex) {
        String message = String.format("Thiếu tham số bắt buộc '%s'", ex.getParameterName());
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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(AuditWriteException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuditWriteException(AuditWriteException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getCode(),
                ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleNotFound(Exception ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                "Không tìm thấy đường dẫn yêu cầu"
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** Phương thức HTTP không hỗ trợ trên đường dẫn có tồn tại (vd. DELETE /api/areas/{id} đã bỏ ở Step 6) -> 405, kèm header Allow. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        ApiResponse<Object> body = ApiResponse.error(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "METHOD_NOT_ALLOWED",
                "Phương thức " + ex.getMethod() + " không được hỗ trợ cho đường dẫn này"
        );
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Xử lý trường hợp client chủ động ngắt kết nối trong quá trình stream video / tải file.
     * Tránh lỗi HttpMessageNotWritableException và không ghi thêm dữ liệu vào socket đã đóng.
     */
    @ExceptionHandler({
            AsyncRequestNotUsableException.class,
            ClientAbortException.class
    })
    public void handleClientDisconnect(Exception ex) {
        log.debug("Client aborted or disconnected stream/connection: {}", ex.getMessage());
    }

    /**
     * Bắt các ngoại lệ I/O phát sinh trong quá trình truyền dữ liệu mạng / stream.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Object>> handleIOException(IOException ex, HttpServletResponse response) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected/aborted connection (IOException): {}", ex.getMessage());
            return null;
        }
        if (response != null && response.isCommitted()) {
            log.warn("Response already committed, cannot send error response for IOException: {}", ex.getMessage());
            return null;
        }
        log.error("I/O exception: ", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi I/O khi xử lý yêu cầu");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneral(Exception ex, HttpServletResponse response) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected/aborted connection (unhandled in general): {}", ex.getMessage());
            return null;
        }
        if (response != null && response.isCommitted()) {
            log.warn("Response already committed, cannot send error response for: {}", ex.getMessage());
            return null;
        }
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [errorId={}]: ", errorId, ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống. Mã tra cứu: " + errorId);
    }

    private boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")
                    || className.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection was aborted")
                        || lower.contains("forcibly closed by the remote host")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private ResponseEntity<ApiResponse<Object>> buildResponse(HttpStatus status, String message) {
        ApiResponse<Object> body = ApiResponse.error(
                status.value(),
                status.getReasonPhrase(),
                message
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
