package com.fa26se040.icss.util;

import java.security.SecureRandom;

/**
 * BE-06 (Lucas chốt 12/11): mật khẩu hệ thống tự sinh (cấp tài khoản, nạp tài khoản theo lô) luôn có ít nhất
 * 1 chữ cái và 1 chữ số — cùng quy tắc độ mạnh với mật khẩu người dùng tự đặt (AuthService.validateNewPassword).
 *
 * Sinh lại cả chuỗi tới khi đạt (rejection sampling) thay vì chèn ký tự vào vị trí cố định: phân phối vẫn đều trên
 * các chuỗi hợp lệ. Với độ dài 12 và bảng chữ có chữ + số, xác suất phải sinh lại rất nhỏ.
 */
public final class TemporaryPasswordGenerator {

    private TemporaryPasswordGenerator() {
    }

    /**
     * @param length   độ dài, tối thiểu 2 (đủ chỗ cho 1 chữ cái và 1 chữ số)
     * @param alphabet bảng ký tự, phải có cả chữ cái lẫn chữ số
     * @param random   nguồn ngẫu nhiên của service gọi
     */
    public static String generate(int length, String alphabet, SecureRandom random) {
        if (length < 2) {
            throw new IllegalArgumentException("Temporary password length must be at least 2");
        }
        if (alphabet.chars().noneMatch(Character::isLetter) || alphabet.chars().noneMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Alphabet must contain both letters and digits");
        }
        while (true) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String candidate = sb.toString();
            if (hasLetterAndDigit(candidate)) {
                return candidate;
            }
        }
    }

    public static boolean hasLetterAndDigit(String value) {
        return value != null
                && value.codePoints().anyMatch(Character::isLetter)
                && value.codePoints().anyMatch(Character::isDigit);
    }
}
