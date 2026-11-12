package com.fa26se040.icss.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Happy Path: writes 401 status with UTF-8 application/json body parsed cleanly by Jackson")
    void writeUnauthorizedResponse_standardMessage_serializesValidJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityConfig.writeUnauthorizedResponse(
                response,
                new InsufficientAuthenticationException("Full authentication is required")
        );

        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentType());
        assertTrue(response.getContentType().toLowerCase().contains("application/json"));
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());

        JsonNode json = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("Unauthorized", json.get("error").asText());
        assertEquals("Full authentication is required", json.get("message").asText());
    }

    @Test
    @DisplayName("Security / JSON Escape: quotes, backslashes, newlines and Vietnamese text never break JSON structure")
    void writeUnauthorizedResponse_quotesAndUnicode_escapedSafely() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String adversarial = "Phiên \"hết hạn\", \"role\":\"ADMIN\" \\ chi tiết\ndòng 2";

        SecurityConfig.writeUnauthorizedResponse(
                response,
                new InsufficientAuthenticationException(adversarial)
        );

        String raw = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(raw);
        assertEquals("Unauthorized", json.get("error").asText());
        assertEquals(adversarial, json.get("message").asText());
        assertFalse(json.has("role"), "Escaped message must not inject extra top-level JSON fields");
    }

    @Test
    @DisplayName("Boundary: null authException or null message falls back cleanly without NPE")
    void writeUnauthorizedResponse_nullInputs_safeFallback() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException nullMsgEx = new InsufficientAuthenticationException(null);

        SecurityConfig.writeUnauthorizedResponse(response, nullMsgEx);

        JsonNode json = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("Unauthorized", json.get("error").asText());
        assertTrue(json.has("message"));
        assertEquals("", json.get("message").asText());
    }
}
