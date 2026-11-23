package com.fa26se040.icss.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends RuntimeException {

    /** Trường bị trùng → câu báo (giữ thứ tự); rỗng nếu không gắn theo trường. Trả ở {@code data} của response 409. */
    private final Map<String, String> fieldErrors;

    public DuplicateResourceException(String message) {
        this(message, Map.of());
    }

    public DuplicateResourceException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
