package com.fa26se040.icss.exception;

import lombok.Getter;

@Getter
public class AreaException extends RuntimeException {

    private final AreaErrorCode errorCode;
    private final Object[] args;

    public AreaException(AreaErrorCode errorCode, Object... args) {
        super(formatMessage(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    public AreaException(AreaErrorCode errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getMessageTemplate());
        this.errorCode = errorCode;
        this.args = new Object[]{customMessage};
    }

    private static String formatMessage(AreaErrorCode errorCode, Object[] args) {
        if (errorCode == null || errorCode.getMessageTemplate() == null) {
            return "";
        }
        String template = errorCode.getMessageTemplate();
        if (args == null || args.length == 0) {
            return template;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{[A-Za-z0-9_]+\\}");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        StringBuffer sb = new StringBuffer();
        int argIndex = 0;
        while (matcher.find()) {
            if (argIndex < args.length && args[argIndex] != null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(args[argIndex])));
                argIndex++;
            } else {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
