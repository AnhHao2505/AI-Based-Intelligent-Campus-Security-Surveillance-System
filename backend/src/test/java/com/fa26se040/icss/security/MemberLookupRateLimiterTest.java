package com.fa26se040.icss.security;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.RateLimitExceededException;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberLookupRateLimiterTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private MemberLookupRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(systemConfigService.getInt(ConfigKey.SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE)).thenReturn(3);
    }

    @Test
    @DisplayName("Gọi trong ngưỡng cho phép không ném ngoại lệ")
    void checkRateLimit_WithinLimit_Success() {
        String email = "student.tuan@fpt.edu.vn";
        assertDoesNotThrow(() -> {
            rateLimiter.checkRateLimit(email);
            rateLimiter.checkRateLimit(email);
            rateLimiter.checkRateLimit(email);
        });
    }

    @Test
    @DisplayName("Gọi vượt ngưỡng cho phép ném RateLimitExceededException")
    void checkRateLimit_ExceedsLimit_ThrowsRateLimitExceededException() {
        String email = "student.tuan@fpt.edu.vn";
        rateLimiter.checkRateLimit(email);
        rateLimiter.checkRateLimit(email);
        rateLimiter.checkRateLimit(email);

        RateLimitExceededException ex = assertThrows(
                RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit(email)
        );

        assertTrue(ex.getMessage().contains("vượt quá số lần tra cứu"));
    }
}
