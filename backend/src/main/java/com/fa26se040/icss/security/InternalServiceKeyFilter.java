package com.fa26se040.icss.security;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Bảo vệ endpoint nội bộ AI service → backend bằng khoá dùng chung (header X-Service-Key).
 *
 * <p>Chỉ áp cho POST /api/access-control/check-entry và /api/internal/**. Các path này vẫn permitAll ở tầng JWT
 * (AI service không có JWT), filter này là lớp chặn duy nhất. Thiếu hoặc sai khoá → 401, body ApiResponse.</p>
 *
 * <p>Khoá đọc từ icss.internal.service-key (env INTERNAL_SERVICE_KEY). Khoá rỗng hoặc ngắn hơn
 * {@value #MIN_KEY_LENGTH} ký tự → backend không khởi động.</p>
 */
@Slf4j
@Component
public class InternalServiceKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Key";
    static final int MIN_KEY_LENGTH = 32;

    private static final List<RequestMatcher> PROTECTED_PATHS = List.of(
            new AntPathRequestMatcher("/api/access-control/check-entry", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/internal/**")
    );

    private final byte[] expectedKey;
    private final ObjectMapper objectMapper;

    public InternalServiceKeyFilter(@Value("${icss.internal.service-key:}") String serviceKey, ObjectMapper objectMapper) {
        if (serviceKey == null || serviceKey.isBlank() || serviceKey.length() < MIN_KEY_LENGTH) {
            // Không đưa giá trị khoá vào thông báo
            throw new IllegalStateException("Thiếu khoá dịch vụ nội bộ: icss.internal.service-key (env INTERNAL_SERVICE_KEY) "
                    + "chưa được đặt hoặc ngắn hơn " + MIN_KEY_LENGTH + " ký tự. Backend không khởi động khi thiếu khoá.");
        }
        this.expectedKey = serviceKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PROTECTED_PATHS.stream().noneMatch(matcher -> matcher.matches(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Từ chối gọi endpoint nội bộ {} {} từ {}: {} header {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                provided == null ? "thiếu" : "sai", HEADER);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Thiếu hoặc sai khoá dịch vụ nội bộ (" + HEADER + ")"
        ));
    }
}
