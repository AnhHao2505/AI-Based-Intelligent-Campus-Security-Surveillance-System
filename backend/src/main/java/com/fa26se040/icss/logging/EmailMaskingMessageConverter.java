package com.fa26se040.icss.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fa26se040.icss.util.LogMasker;

/**
 * L24: thay {@code %m / %msg / %message} trong logback-spring.xml. Che mọi chuỗi dạng e-mail trong thông điệp log (đã
 * điền tham số) theo đúng quy tắc {@link LogMasker#maskEmail}; chuỗi đã che tay ({@code h***@miền}) không bị che lần nữa.
 */
public class EmailMaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.maskEmailsIn(super.convert(event));
    }
}
