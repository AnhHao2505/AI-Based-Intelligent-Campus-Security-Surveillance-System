package com.fa26se040.icss.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AnnotatedStreamTokenServiceTest {

    private static final String TEST_KEY = "0123456789abcdef0123456789abcdef";
    private static final String TEST_CAMERA = "CAM-001";
    private static final long TEST_EXP = 1893456000L;
    private static final String EXPECTED_TOKEN = "1893456000.83208ab377034e26a5a0ba3f022dae5dcc22f673d99c2de3ab46f8d39f93fcde";

    @Test
    @DisplayName("T1: Vector chung Java/Python khớp chính xác token và chữ ký")
    void testT1CommonVector() {
        String token = AnnotatedStreamTokenService.generateToken(TEST_CAMERA, TEST_EXP, TEST_KEY);
        assertEquals(EXPECTED_TOKEN, token);

        // Không phân biệt chữ hoa/thường mã camera
        String lowerToken = AnnotatedStreamTokenService.generateToken("cam-001", TEST_EXP, TEST_KEY);
        assertEquals(EXPECTED_TOKEN, lowerToken);
    }

    @Test
    @DisplayName("T4: Issue token tạo token và hạn dùng đúng TTL")
    void testIssueTokenTTL() {
        long ttlSeconds = 60L;
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(TEST_KEY, ttlSeconds);

        long before = Instant.now().getEpochSecond();
        AnnotatedStreamTokenService.TokenResponse response = service.issue(TEST_CAMERA);
        long after = Instant.now().getEpochSecond();

        assertNotNull(response);
        assertNotNull(response.token());
        assertTrue(response.expiresAt() >= before + ttlSeconds);
        assertTrue(response.expiresAt() <= after + ttlSeconds);

        assertTrue(response.token().startsWith(response.expiresAt() + "."));
    }
}
