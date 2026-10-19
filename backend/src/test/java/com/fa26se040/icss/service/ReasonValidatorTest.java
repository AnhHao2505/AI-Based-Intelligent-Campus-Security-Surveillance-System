package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.enums.ConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/** Checklist 6.1: biên độ dài lý do theo cấu hình REASON_MIN_LENGTH / REASON_MAX_LENGTH, đo sau khi trim. */
@ExtendWith(MockitoExtension.class)
class ReasonValidatorTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private ReasonValidator reasonValidator;

    @BeforeEach
    void defaultLimits() {
        limits(10, 500);
    }

    private void limits(int min, int max) {
        lenient().when(systemConfigService.getInt(ConfigKey.REASON_MIN_LENGTH)).thenReturn(min);
        lenient().when(systemConfigService.getInt(ConfigKey.REASON_MAX_LENGTH)).thenReturn(max);
    }

    private static String text(int length) {
        return "a".repeat(length);
    }

    @Test
    @DisplayName("Biên: min−1 sai, min đúng, max đúng, max+1 sai")
    void boundaries() {
        assertFalse(reasonValidator.isValid(text(9)));
        assertTrue(reasonValidator.isValid(text(10)));
        assertTrue(reasonValidator.isValid(text(500)));
        assertFalse(reasonValidator.isValid(text(501)));
    }

    @Test
    @DisplayName("Khoảng trắng hai đầu không tính; chỉ khoảng trắng hoặc null là sai; validate trả lý do đã trim")
    void whitespaceIsTrimmed() {
        assertTrue(reasonValidator.isValid("   " + text(10) + "   "));
        assertFalse(reasonValidator.isValid("    " + text(9) + "    "));
        assertFalse(reasonValidator.isValid("               "));
        assertFalse(reasonValidator.isValid(null));
        assertEquals(text(10), reasonValidator.validate("  " + text(10) + "\n"));
        assertTrue(reasonValidator.isValid("  " + text(498) + "  "));
    }

    @Test
    @DisplayName("Sai độ dài -> IllegalArgumentException, câu báo ghi giá trị cấu hình hiện hành")
    void messageUsesCurrentLimits() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> reasonValidator.validate(text(9)));
        assertEquals("Lý do phải có từ 10 đến 500 ký tự", ex.getMessage());

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> reasonValidator.validate(text(501), "Lý do từ chối"));
        assertEquals("Lý do từ chối phải có từ 10 đến 500 ký tự", rejected.getMessage());
    }

    @Test
    @DisplayName("Đọc cấu hình mỗi lần gọi: đổi min 10 -> 20 thì lần gọi sau áp ngay")
    void readsConfigOnEveryCall() {
        assertTrue(reasonValidator.isValid(text(15)));

        limits(20, 500);

        assertFalse(reasonValidator.isValid(text(15)));
        assertTrue(reasonValidator.isValid(text(20)));
        assertEquals(new ReasonLimits(20, 500), reasonValidator.limits());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> reasonValidator.validate(text(19)));
        assertEquals("Lý do phải có từ 20 đến 500 ký tự", ex.getMessage());
    }

    @Test
    @DisplayName("Cặp cấu hình hỏng (min < 1, min ≥ max, max > độ dài cột) -> dùng mặc định 10 / 500")
    void inconsistentConfigFallsBackToDefaults() {
        for (int[] bad : new int[][]{{0, 500}, {50, 50}, {60, 40}, {10, ReasonValidator.STORAGE_MAX_LENGTH + 1}}) {
            limits(bad[0], bad[1]);
            assertEquals(ReasonValidator.DEFAULT_LIMITS, reasonValidator.limits(), bad[0] + "/" + bad[1]);
        }
        assertEquals(new ReasonLimits(10, 500), ReasonValidator.DEFAULT_LIMITS);
    }

    @Test
    @DisplayName("isConsistent: 1 ≤ min < max ≤ 500")
    void consistency() {
        assertTrue(ReasonValidator.isConsistent(1, 2));
        assertTrue(ReasonValidator.isConsistent(10, 500));
        assertFalse(ReasonValidator.isConsistent(0, 500));
        assertFalse(ReasonValidator.isConsistent(20, 20));
        assertFalse(ReasonValidator.isConsistent(10, 501));
    }
}
