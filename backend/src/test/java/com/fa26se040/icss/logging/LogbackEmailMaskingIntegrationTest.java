package com.fa26se040.icss.logging;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L24: logback-spring.xml thật (nạp khi Spring Boot khởi động) che e-mail ở console cho INFO / WARN / ERROR, kể cả trong
 * message của exception, và giữ định dạng dòng log mặc định của Spring Boot.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackEmailMaskingIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LogbackEmailMaskingIntegrationTest.class);

    /** Dòng log mặc định của Spring Boot: thời điểm ISO, mức, PID, "---", [thread], logger, " : ". */
    private static final Pattern SPRING_BOOT_LINE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2} +(INFO|WARN|ERROR) \\d+ --- .*\\[.*\\] .*"
                    + "LogbackEmailMaskingIntegrationTest +: ");

    @Test
    @DisplayName("L24: INFO / WARN / ERROR có e-mail (tham số, chuỗi mẫu, exception, Caused by) -> console chỉ có dạng đã che")
    void consoleOutput_masksEmails(CapturedOutput output) {
        log.info("L24 info login {}", "lucas.info@fpt.edu.vn");
        log.warn("L24 warn reset for lucas.warn@fpt.edu.vn, already masked h***@fpt.edu.vn");
        log.error("L24 error mail to {}", "lucas.error@fpt.edu.vn",
                new IllegalStateException("SMTP refused lucas.ex@fpt.edu.vn",
                        new RuntimeException("550 <lucas.cause@gmail.com> rejected")));

        String out = output.getOut() + output.getErr();
        assertThat(out)
                .doesNotContain("lucas.info@fpt.edu.vn", "lucas.warn@fpt.edu.vn", "lucas.error@fpt.edu.vn",
                        "lucas.ex@fpt.edu.vn", "lucas.cause@gmail.com")
                .contains("L24 info login l***@fpt.edu.vn")
                .contains("L24 warn reset for l***@fpt.edu.vn, already masked h***@fpt.edu.vn")
                .contains("L24 error mail to l***@fpt.edu.vn")
                .contains("java.lang.IllegalStateException: SMTP refused l***@fpt.edu.vn")
                .contains("Caused by: java.lang.RuntimeException: 550 <l***@gmail.com> rejected");
        for (String marker : new String[]{"L24 info", "L24 warn", "L24 error"}) {
            String line = out.lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
            assertThat(SPRING_BOOT_LINE.matcher(line.replaceAll("\u001B\\[[;\\d]*m", "")).find())
                    .as("giữ định dạng Spring Boot: %s", line).isTrue();
        }
    }
}
