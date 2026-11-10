package com.fa26se040.icss.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-AU2 (R2): hàm che e-mail dùng chung cho log của AuthService / LoginAttemptLimiter. */
class LogMaskerTest {

    @Test
    @DisplayName("E-mail thường -> giữ ký tự đầu phần trước @ và tên miền")
    void normalEmail() {
        assertThat(LogMasker.maskEmail("hao.nguyen@fpt.edu.vn")).isEqualTo("h***@fpt.edu.vn");
    }

    @Test
    @DisplayName("Phần trước @ chỉ 1 ký tự -> vẫn che, không lộ độ dài")
    void singleCharLocalPart() {
        assertThat(LogMasker.maskEmail("h@fpt.edu.vn")).isEqualTo("h***@fpt.edu.vn");
    }

    @Test
    @DisplayName("null -> \"null\"")
    void nullEmail() {
        assertThat(LogMasker.maskEmail(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("Chuỗi không có @ -> che toàn bộ")
    void noAtSign() {
        assertThat(LogMasker.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(LogMasker.maskEmail("")).isEqualTo("***");
    }

    @Test
    @DisplayName("Không có phần trước @ / có khoảng trắng hai đầu")
    void edgeCases() {
        assertThat(LogMasker.maskEmail("@fpt.edu.vn")).isEqualTo("***@fpt.edu.vn");
        assertThat(LogMasker.maskEmail("  an@fpt.edu.vn ")).isEqualTo("a***@fpt.edu.vn");
    }

    @Test
    @DisplayName("maskEmailsIn: che mọi e-mail trong thông điệp lỗi, giữ phần còn lại; null -> null")
    void maskEmailsInText() {
        assertThat(LogMasker.maskEmailsIn("550 5.1.1 <hao.nguyen@fpt.edu.vn>: Recipient address rejected; cc an@gmail.com"))
                .isEqualTo("550 5.1.1 <h***@fpt.edu.vn>: Recipient address rejected; cc a***@gmail.com");
        assertThat(LogMasker.maskEmailsIn("Connection refused")).isEqualTo("Connection refused");
        assertThat(LogMasker.maskEmailsIn(null)).isNull();
    }
}
