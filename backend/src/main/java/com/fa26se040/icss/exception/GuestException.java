package com.fa26se040.icss.exception;

import lombok.Getter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lỗi nghiệp vụ module khách. Tham số thay lần lượt vào các chỗ {..} của template.
 */
@Getter
public class GuestException extends RuntimeException {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_]+\\}");

    private final GuestErrorCode errorCode;

    public GuestException(GuestErrorCode errorCode, Object... args) {
        super(format(errorCode.getMessageTemplate(), args));
        this.errorCode = errorCode;
    }

    private static String format(String template, Object[] args) {
        if (args == null || args.length == 0) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (m.find()) {
            String value = i < args.length ? String.valueOf(args[i++]) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
