package com.fa26se040.icss.dto;

/**
 * KT-01: chính sách mật khẩu mới người dùng tự đặt (BE-06, V119), trả cho form đặt lại mật khẩu (chưa đăng nhập).
 * minLength đọc từ PASSWORD_MIN_LENGTH; requireLetterAndDigit = quy tắc cố định "có ít nhất 1 chữ cái và 1 chữ số"
 * (AuthService.validateNewPassword). Backend luôn kiểm lại khi đặt lại mật khẩu.
 */
public record PasswordPolicyResponse(int minLength, boolean requireLetterAndDigit) {
}
