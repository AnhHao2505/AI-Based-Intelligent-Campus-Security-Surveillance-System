package com.fa26se040.icss.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.fa26se040.icss.util.LogMasker;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * L24: thay {@code %wEx} (từ khoá exception mặc định của Spring Boot) trong logback-spring.xml. Che e-mail trong toàn bộ
 * chuỗi stack trace: thông điệp của exception, mọi "Caused by", "Suppressed". Định dạng giữ nguyên như Spring Boot.
 */
public class EmailMaskingExtendedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasker.maskEmailsIn(super.throwableProxyToString(tp));
    }
}
