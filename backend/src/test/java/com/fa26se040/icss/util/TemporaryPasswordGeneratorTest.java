package com.fa26se040.icss.util;

import com.fa26se040.icss.service.UserBulkImportService;
import com.fa26se040.icss.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BE-06 (Lucas chốt 12/11): mật khẩu hệ thống tự sinh luôn có ít nhất 1 chữ cái và 1 chữ số. */
class TemporaryPasswordGeneratorTest {

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Test
    void generatedPasswords_alwaysContainLetterAndDigit() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 5_000; i++) {
            String pw = TemporaryPasswordGenerator.generate(12, ALNUM, random);
            assertThat(pw).hasSize(12);
            assertThat(TemporaryPasswordGenerator.hasLetterAndDigit(pw)).as(pw).isTrue();
            assertThat(pw.chars().allMatch(c -> ALNUM.indexOf(c) >= 0)).as(pw).isTrue();
        }
    }

    @Test
    void shortLength_resamplesUntilBothKindsPresent() {
        // Độ dài 2 trên bảng 26 chữ + 1 số: phần lớn lần sinh đầu thiếu chữ số -> phải sinh lại, không bao giờ trả chuỗi thiếu
        String alphabet = "abcdefghijklmnopqrstuvwxyz7";
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 500; i++) {
            String pw = TemporaryPasswordGenerator.generate(2, alphabet, random);
            assertThat(pw).contains("7").matches(".*[a-z].*");
        }
    }

    @Test
    void invalidArguments_rejected() {
        SecureRandom random = new SecureRandom();
        assertThatThrownBy(() -> TemporaryPasswordGenerator.generate(1, ALNUM, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemporaryPasswordGenerator.generate(12, "abcdef", random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemporaryPasswordGenerator.generate(12, "012345", random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userServiceAndBulkImport_generatedPasswordsHaveLetterAndDigit() {
        // Gọi đúng hàm private của 2 service đang cấp mật khẩu tạm (tạo tài khoản, nạp theo lô)
        UserService userService = Mockito.mock(UserService.class, Mockito.CALLS_REAL_METHODS);
        UserBulkImportService bulk = new UserBulkImportService(null, null);
        for (int i = 0; i < 2_000; i++) {
            String a = ReflectionTestUtils.invokeMethod(userService, "generateRandomPassword", 12);
            String b = ReflectionTestUtils.invokeMethod(bulk, "generateRandomPassword", 12);
            assertThat(a).hasSize(12);
            assertThat(b).hasSize(12);
            assertThat(TemporaryPasswordGenerator.hasLetterAndDigit(a)).as(a).isTrue();
            assertThat(TemporaryPasswordGenerator.hasLetterAndDigit(b)).as(b).isTrue();
        }
    }
}
