package com.fa26se040.icss.config;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fa26se040.icss.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final com.fasterxml.jackson.databind.ObjectMapper UNAUTHORIZED_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalServiceKeyFilter internalServiceKeyFilter;

    static void writeUnauthorizedResponse(
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException authException
    ) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        String message = authException != null && authException.getMessage() != null
                ? authException.getMessage()
                : "";
        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("error", "Unauthorized");
        payload.put("message", message);
        response.getWriter().write(UNAUTHORIZED_OBJECT_MAPPER.writeValueAsString(payload));
    }

    /**
     * BE-01: 403 do quy tắc URL ({@link RoleRestrictedEndpoints}) trả cùng định dạng với 403 của {@code @PreAuthorize}
     * (GlobalExceptionHandler#handleAccessDenied), để frontend đọc message / errorCode như cũ.
     */
    static void writeForbiddenResponse(
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException accessDeniedException
    ) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        String detail = accessDeniedException != null && accessDeniedException.getMessage() != null
                ? accessDeniedException.getMessage()
                : "Access Denied";
        ApiResponse<Object> body = ApiResponse.error(
                HttpServletResponse.SC_FORBIDDEN,
                org.springframework.http.HttpStatus.FORBIDDEN.getReasonPhrase(),
                "Access denied: " + detail
        );
        response.getWriter().write(UNAUTHORIZED_OBJECT_MAPPER.writeValueAsString(body));
    }

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                writeUnauthorizedResponse(response, authException))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeForbiddenResponse(response, accessDeniedException))
                )
                .authorizeHttpRequests(auth -> applyRoleRestrictedRules(auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Endpoint nội bộ AI → backend: không dùng JWT, InternalServiceKeyFilter kiểm header X-Service-Key
                        .requestMatchers("/api/access-control/check-entry", "/api/internal/**").permitAll()
                        .requestMatchers("/ws-security/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // BE-03: thẻ <video> không gửi header Authorization; controller bắt buộc query token ngắn hạn
                        .requestMatchers(HttpMethod.GET, "/api/cameras/recordings/stream").permitAll())
                        .anyRequest().authenticated()
                )
                .addFilterBefore(internalServiceKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** BE-01: chặn vai trò trước khi bind / validate tham số; vai trò trùng {@code @PreAuthorize} của handler. */
    private static AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry applyRoleRestrictedRules(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        for (RoleRestrictedEndpoints.Rule rule : RoleRestrictedEndpoints.RULES) {
            auth.requestMatchers(rule.method(), rule.pattern()).hasAnyRole(rule.rolesArray());
        }
        return auth;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Cache-Control"));
        configuration.setExposedHeaders(Collections.singletonList("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
