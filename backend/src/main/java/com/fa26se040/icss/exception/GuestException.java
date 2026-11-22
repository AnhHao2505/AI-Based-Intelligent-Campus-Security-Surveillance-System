package com.fa26se040.icss.exception;

import lombok.Getter;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lỗi nghiệp vụ module khách. Tham số thay lần lượt vào các chỗ {..} của template.
 */
@Getter
public class GuestException extends RuntimeException {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_]+\\}");

    private final GuestErrorCode errorCode;
    /** Chi tiết kèm theo cho FE (vd. guestIndex của khách bị lỗi), null nếu không có. */
    private final Map<String, Object> data;

    public GuestException(GuestErrorCode errorCode, Object... args) {
        super(format(errorCode.getMessageTemplate(), args));
        this.errorCode = errorCode;
        this.data = null;
    }

    private GuestException(GuestErrorCode errorCode, String message, Map<String, Object> data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * Lỗi của một khách khi tạo lượt kèm ảnh: giữ mã lỗi gốc, câu mở đầu "Khách thứ n (họ tên): ",
     * data.guestIndex (đếm từ 0) để FE tô đúng dòng khách.
     */
    public static GuestException forGuest(int index, String fullName, GuestException cause) {
        return new GuestException(cause.getErrorCode(),
                "Khách thứ " + (index + 1) + " (" + fullName + "): " + cause.getMessage(),
                Map.of("guestIndex", index));
    }

    /** Câu thông báo của mã lỗi (đã nội suy) khi cần dùng ở module khác, vd. đăng ký khuôn mặt người dùng. */
    public static String render(GuestErrorCode errorCode, Object... args) {
        return format(errorCode.getMessageTemplate(), args);
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
