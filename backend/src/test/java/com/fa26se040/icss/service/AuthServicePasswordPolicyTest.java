package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** BE-06: quy tắc mật khẩu mới đọc PASSWORD_MIN_LENGTH lúc gọi, câu lỗi nội suy giá trị hiện hành. */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordPolicyTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("ADMIN nâng PASSWORD_MIN_LENGTH lên 12 -> mật khẩu 8 ký tự bị chặn, câu lỗi nêu 12")
    void minLengthFromConfig_messageInterpolated() {
        when(systemConfigService.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(12);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.validateNewPassword("abcd1234"));
        assertEquals("Mật khẩu mới phải có ít nhất 12 ký tự, gồm cả chữ cái và chữ số.", ex.getMessage());
        assertDoesNotThrow(() -> authService.validateNewPassword("abcdefgh1234"));
    }

    @Test
    @DisplayName("Đủ độ dài nhưng thiếu chữ hoặc thiếu số -> chặn; chữ có dấu tính là chữ cái; null -> chặn")
    void strengthRules() {
        when(systemConfigService.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(8);

        assertThrows(IllegalArgumentException.class, () -> authService.validateNewPassword("abcdefghij"));
        assertThrows(IllegalArgumentException.class, () -> authService.validateNewPassword("1234567890"));
        assertThrows(IllegalArgumentException.class, () -> authService.validateNewPassword("!@#$%^&*()"));
        assertThrows(IllegalArgumentException.class, () -> authService.validateNewPassword(null));
        assertDoesNotThrow(() -> authService.validateNewPassword("mậtkhẩu1"));
        assertDoesNotThrow(() -> authService.validateNewPassword("Abc-1234"));
    }
}
