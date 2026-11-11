package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;

/**
 * V115 (EV-N7): lịch sự kiện không được chạm khung ban đêm AI_AFTER_HOUR_START–AI_AFTER_HOUR_END, kể cả lúc job kích hoạt.
 * Các test cũ dựng lịch / phiên theo giờ hiện tại (now + X) nên kết quả phụ thuộc giờ chạy (ban đêm, CI). Lớp test đó tắt
 * khung ban đêm (START = END: không có ban đêm, xem AfterHoursWindow.isWithin) trong @BeforeEach và trả lại trong @AfterEach,
 * cùng mẫu AssignedPersonnelUntilClosingTimeTest. Không tắt toàn cục: test khác (check-entry, sự cố) có thể dựa vào 22:00–06:00.
 * Test của chính EV-N7 / EV-N8 đặt khung cụ thể (NightWindowEventScheduleIntegrationTest).
 */
public final class AfterHoursTestSupport {

    private AfterHoursTestSupport() {
    }

    /** Tắt khung ban đêm; trả [START, END] trước đó để {@link #restore}. */
    public static String[] disableNightWindow(SystemConfigService configService, String actorEmail) {
        String start = configService.getString(ConfigKey.AI_AFTER_HOUR_START);
        String end = configService.getString(ConfigKey.AI_AFTER_HOUR_END);
        if (!start.equals(end)) {
            configService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), end, actorEmail);
        }
        return new String[]{start, end};
    }

    /** Trả khung ban đêm về giá trị đã lưu (cache cấu hình không rollback theo transaction test). */
    public static void restore(SystemConfigService configService, String[] previous, String actorEmail) {
        if (previous == null) {
            return;
        }
        if (!previous[1].equals(configService.getString(ConfigKey.AI_AFTER_HOUR_END))) {
            configService.update(ConfigKey.AI_AFTER_HOUR_END.getKey(), previous[1], actorEmail);
        }
        if (!previous[0].equals(configService.getString(ConfigKey.AI_AFTER_HOUR_START))) {
            configService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), previous[0], actorEmail);
        }
    }
}
