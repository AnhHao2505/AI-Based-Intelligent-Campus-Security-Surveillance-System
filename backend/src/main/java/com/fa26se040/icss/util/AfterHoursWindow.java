package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Khung ngoài giờ AI_AFTER_HOUR_START–AI_AFTER_HOUR_END (BR-SEV-02), giờ Asia/Ho_Chi_Minh.
 * Cùng ngữ nghĩa [start, end) với CameraAccessLogService.isWithin; lặp có chủ đích vì chưa được sửa file đó
 * (gộp lại khi được phép refactor). Đọc cấu hình mỗi lần gọi nên sửa có hiệu lực ngay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AfterHoursWindow {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SystemConfigService systemConfigService;

    /** at (quy về giờ Việt Nam) có nằm trong khung ngoài giờ không. */
    public boolean isAfterHours(OffsetDateTime at) {
        LocalTime time = at.atZoneSameInstant(VN_ZONE).toLocalTime();
        return isWithin(time, readTime(ConfigKey.AI_AFTER_HOUR_START), readTime(ConfigKey.AI_AFTER_HOUR_END));
    }

    /**
     * t thuộc khung [start, end): start inclusive, end exclusive.
     * start &lt; end: khung trong ngày; start &gt; end: khung qua nửa đêm; start == end: khung rỗng.
     */
    public static boolean isWithin(LocalTime t, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        if (start.isAfter(end)) {
            return !t.isBefore(start) || t.isBefore(end);
        }
        return false;
    }

    private LocalTime readTime(ConfigKey key) {
        String raw = systemConfigService.getString(key);
        try {
            return LocalTime.parse(raw != null ? raw.trim() : key.getDefaultValue());
        } catch (DateTimeParseException e) {
            log.warn("Giá trị [{}] của cấu hình [{}] không đúng định dạng HH:mm. Dùng mặc định: {}",
                    raw, key.getKey(), key.getDefaultValue());
            return LocalTime.parse(key.getDefaultValue());
        }
    }
}
