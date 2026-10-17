package com.fa26se040.icss.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-fast khi khởi động: khoá dịch vụ nội bộ rỗng hoặc ngắn hơn 32 ký tự → IllegalStateException, không lộ giá trị khoá.
 */
class InternalServiceKeyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("K-05: Khoá null / rỗng / toàn khoảng trắng → không khởi động")
    void k05_blankKey_FailsFast() {
        assertThrows(IllegalStateException.class, () -> new InternalServiceKeyFilter(null, objectMapper));
        assertThrows(IllegalStateException.class, () -> new InternalServiceKeyFilter("", objectMapper));
        assertThrows(IllegalStateException.class,
                () -> new InternalServiceKeyFilter(" ".repeat(InternalServiceKeyFilter.MIN_KEY_LENGTH), objectMapper));
    }

    @Test
    @DisplayName("K-05: Khoá 31 ký tự → không khởi động, thông báo không chứa giá trị khoá; 32 ký tự → khởi động được")
    void k05_shortKey_FailsFastWithoutLeakingValue() {
        String shortKey = "k".repeat(InternalServiceKeyFilter.MIN_KEY_LENGTH - 2) + "Z9";
        String tooShort = shortKey.substring(1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new InternalServiceKeyFilter(tooShort, objectMapper));
        assertFalse(e.getMessage().contains(tooShort));
        assertFalse(e.getMessage().contains("Z9"));

        assertDoesNotThrow(() -> new InternalServiceKeyFilter(shortKey, objectMapper));
    }
}
