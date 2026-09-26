package com.fa26se040.icss.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    @Builder.Default
    private String timestamp = OffsetDateTime.now().toString();

    private int httpCode;
    private String message;
    private String errorCode;
    private T data;

    public String getCode() {
        return errorCode;
    }

    public int getStatus() {
        return httpCode;
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .httpCode(200)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .httpCode(200)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> created(T data, String message) {
        return ApiResponse.<T>builder()
                .httpCode(201)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(int httpCode, String errorCode, String message) {
        return ApiResponse.<T>builder()
                .httpCode(httpCode)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(int httpCode, String errorCode, String message, T data) {
        return ApiResponse.<T>builder()
                .httpCode(httpCode)
                .errorCode(errorCode)
                .message(message)
                .data(data)
                .build();
    }
}
