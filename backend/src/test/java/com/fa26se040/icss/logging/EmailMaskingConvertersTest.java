package com.fa26se040.icss.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fa26se040.icss.util.LogMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** L24: converter che e-mail của logback (thông điệp và exception), không cần Spring context. */
class EmailMaskingConvertersTest {

    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("test.masking");

    private LoggingEvent event(Level level, String format, Throwable t, Object... args) {
        return new LoggingEvent(Logger.class.getName(), logger, level, format, t, args);
    }

    @Test
    @DisplayName("%m: e-mail trong tham số và trong chuỗi mẫu đều được che theo LogMasker.maskEmail, phần còn lại giữ nguyên")
    void message_masksEveryEmail() {
        EmailMaskingMessageConverter converter = new EmailMaskingMessageConverter();
        String out = converter.convert(event(Level.INFO, "Login failed for {} (cc admin.binh@fpt.edu.vn), attempt {}", null,
                "hao.nguyen@fpt.edu.vn", 3));
        assertThat(out).isEqualTo("Login failed for h***@fpt.edu.vn (cc a***@fpt.edu.vn), attempt 3");
    }

    @Test
    @DisplayName("%m: chuỗi đã che tay (h***@miền, ***@miền) không bị che lần 2; không có '@' -> giữ nguyên")
    void message_alreadyMasked_unchanged() {
        EmailMaskingMessageConverter converter = new EmailMaskingMessageConverter();
        assertThat(converter.convert(event(Level.WARN, "Reset link for {} and {}", null, "h***@fpt.edu.vn", "***@gmail.com")))
                .isEqualTo("Reset link for h***@fpt.edu.vn and ***@gmail.com");
        assertThat(converter.convert(event(Level.WARN, "Connection refused to {}:{}", null, "localhost", 5432)))
                .isEqualTo("Connection refused to localhost:5432");
    }

    @Test
    @DisplayName("%wEx: e-mail trong message của exception và của Caused by đều được che; stack frame giữ nguyên")
    void throwable_masksExceptionMessages() {
        EmailMaskingExtendedThrowableConverter converter = new EmailMaskingExtendedThrowableConverter();
        converter.setContext(context);
        converter.start();
        Exception ex = new IllegalStateException("Cannot send mail to student.tuan@fpt.edu.vn",
                new RuntimeException("550 <guard.an@fpt.edu.vn> rejected"));

        String out = converter.convert(event(Level.ERROR, "send failed", ex));

        assertThat(out).doesNotContain("student.tuan@fpt.edu.vn").doesNotContain("guard.an@fpt.edu.vn")
                .contains("java.lang.IllegalStateException: Cannot send mail to s***@fpt.edu.vn")
                .contains("Caused by: java.lang.RuntimeException: 550 <g***@fpt.edu.vn> rejected")
                .contains("at com.fa26se040.icss.logging.EmailMaskingConvertersTest");
    }

    @Test
    @DisplayName("%wex: cũng che e-mail trong exception")
    void plainThrowable_masks() {
        EmailMaskingThrowableConverter converter = new EmailMaskingThrowableConverter();
        converter.setContext(context);
        converter.start();
        String out = converter.convert(event(Level.ERROR, "x", new IllegalArgumentException("bad hao@fpt.edu.vn")));
        assertThat(out).contains("bad h***@fpt.edu.vn").doesNotContain("hao@fpt.edu.vn");
    }

    @Test
    @DisplayName("Hiệu năng: regex là static final (biên dịch 1 lần); 100.000 dòng log (một nửa có e-mail) che dưới 2 giây")
    void performance_regexCompiledOnce() throws Exception {
        Field field = LogMasker.class.getDeclaredField("EMAIL_IN_TEXT");
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(Pattern.class);

        EmailMaskingMessageConverter converter = new EmailMaskingMessageConverter();
        LoggingEvent withEmail = event(Level.INFO, "Authenticating local user with email: {}", null, "hao.nguyen@fpt.edu.vn");
        LoggingEvent withoutEmail = event(Level.INFO, "SystemConfiguration cache loaded with {} entries", null, 47);
        long start = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            converter.convert(withEmail);
            converter.convert(withoutEmail);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("100k dòng: %d ms", elapsedMs).isLessThan(2_000);
    }
}
