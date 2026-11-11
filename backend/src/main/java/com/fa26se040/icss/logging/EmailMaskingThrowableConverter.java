package com.fa26se040.icss.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.fa26se040.icss.util.LogMasker;
import org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter;

/** L24: thay {@code %wex} trong logback-spring.xml (dùng khi đặt logging.exception-conversion-word=%wex). */
public class EmailMaskingThrowableConverter extends WhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasker.maskEmailsIn(super.throwableProxyToString(tp));
    }
}
