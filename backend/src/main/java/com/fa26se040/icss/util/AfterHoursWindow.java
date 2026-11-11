package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

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

    /** AI_AFTER_HOUR_START hiện hành (đọc cấu hình mỗi lần gọi). */
    public LocalTime startTime() {
        return readTime(ConfigKey.AI_AFTER_HOUR_START);
    }

    /** AI_AFTER_HOUR_END hiện hành (đọc cấu hình mỗi lần gọi). */
    public LocalTime endTime() {
        return readTime(ConfigKey.AI_AFTER_HOUR_END);
    }

    /**
     * EV-N7: thời điểm ban đêm sớm nhất trong khoảng [from, to) theo khung hiện hành (đọc cấu hình mỗi lần gọi).
     * Rỗng = khoảng không chạm khung ban đêm của bất kỳ ngày nào.
     */
    public Optional<OffsetDateTime> firstAfterHoursInstant(OffsetDateTime from, OffsetDateTime to) {
        return firstAfterHoursInstant(from, to, startTime(), endTime());
    }

    /**
     * EV-N7: thời điểm ban đêm sớm nhất trong khoảng [from, to), khung [start, end) lặp lại MỖI ngày theo giờ
     * Asia/Ho_Chi_Minh (cùng ngữ nghĩa {@link #isWithin}: start &lt; end trong ngày, start &gt; end qua nửa đêm).
     * <ul>
     *   <li>start == end: khung rỗng, không có ban đêm -> luôn rỗng.</li>
     *   <li>from đã nằm trong ban đêm -> trả from.</li>
     *   <li>Ngược lại, ban đêm tiếp theo bắt đầu ở lần xuất hiện kế tiếp của start (cùng ngày nếu start còn ở phía trước,
     *   không thì ngày sau) — đúng cho cả khung qua nửa đêm lẫn khung trong ngày, nên không cần duyệt từng ngày:
     *   nếu mốc đó trước to thì khoảng chạm ban đêm, các ngày sau chỉ có mốc muộn hơn.</li>
     * </ul>
     * Khoảng nửa mở: kết thúc đúng lúc bắt đầu ban đêm (to == start) không chạm; bắt đầu đúng lúc hết ban đêm
     * (from == end) không chạm.
     */
    public static Optional<OffsetDateTime> firstAfterHoursInstant(OffsetDateTime from, OffsetDateTime to,
                                                                  LocalTime start, LocalTime end) {
        if (from == null || to == null || !from.isBefore(to) || start.equals(end)) {
            return Optional.empty();
        }
        ZonedDateTime zFrom = from.atZoneSameInstant(VN_ZONE);
        if (isWithin(zFrom.toLocalTime(), start, end)) {
            return Optional.of(from);
        }
        LocalDate day = zFrom.toLocalDate();
        ZonedDateTime nextStart = day.atTime(start).atZone(VN_ZONE);
        if (nextStart.isBefore(zFrom)) {
            nextStart = day.plusDays(1).atTime(start).atZone(VN_ZONE);
        }
        OffsetDateTime candidate = nextStart.toOffsetDateTime();
        return candidate.isBefore(to) ? Optional.of(candidate) : Optional.empty();
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
