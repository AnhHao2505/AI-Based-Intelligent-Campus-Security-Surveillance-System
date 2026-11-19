package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.SystemConfigurationRepository;

/**
 * V115 (EV-N7): lịch sự kiện không được chạm khung ban đêm AI_AFTER_HOUR_START–AI_AFTER_HOUR_END, kể cả lúc job kích hoạt.
 * Các test cũ dựng lịch / phiên theo giờ hiện tại (now + X) nên kết quả phụ thuộc giờ chạy (ban đêm, CI). Lớp test đó tắt
 * khung ban đêm (START = END: khung rỗng, xem AfterHoursWindow.isWithin) trong @BeforeEach và trả lại trong @AfterEach,
 * cùng mẫu AssignedPersonnelUntilClosingTimeTest. Không tắt toàn cục: test khác (check-entry, sự cố) có thể dựa vào 22:00–06:00.
 * Test của chính EV-N7 / EV-N8 đặt khung cụ thể (NightWindowEventScheduleIntegrationTest).
 * #262: SystemConfigService.update từ chối START = END, nên tắt khung bằng cách ghi thẳng dữ liệu kiểu cũ qua repository rồi
 * nạp lại cache; logic khung vẫn chịu được dữ liệu này.
 */
public final class AfterHoursTestSupport {

    private AfterHoursTestSupport() {
    }

    /** Tắt khung ban đêm (ghi thẳng START = END); trả [START, END] trước đó để {@link #restore}. */
    public static String[] disableNightWindow(SystemConfigService configService, SystemConfigurationRepository configRepository) {
        String start = configService.getString(ConfigKey.AI_AFTER_HOUR_START);
        String end = configService.getString(ConfigKey.AI_AFTER_HOUR_END);
        if (!start.equals(end)) {
            writeDirectly(configRepository, ConfigKey.AI_AFTER_HOUR_START, end);
            configService.refreshCache();
        }
        return new String[]{start, end};
    }

    /** Trả khung ban đêm về giá trị đã lưu (cache cấu hình không rollback theo transaction test). */
    public static void restore(SystemConfigService configService, SystemConfigurationRepository configRepository,
                               String[] previous, String actorEmail) {
        if (previous == null) {
            return;
        }
        if (previous[0].equals(previous[1])) {
            // Trước test đã là START = END (vd. lần chạy trước dừng giữa chừng): update từ chối cặp trùng, ghi thẳng như lúc tắt
            writeDirectly(configRepository, ConfigKey.AI_AFTER_HOUR_END, previous[1]);
            writeDirectly(configRepository, ConfigKey.AI_AFTER_HOUR_START, previous[0]);
            configService.refreshCache();
            return;
        }
        // Mặc định trả END trước; END cũ trùng START hiện hành thì trả START trước để không đi qua cặp trùng (#262)
        if (previous[1].equals(configService.getString(ConfigKey.AI_AFTER_HOUR_START))) {
            restoreKey(configService, ConfigKey.AI_AFTER_HOUR_START, previous[0], actorEmail);
        }
        restoreKey(configService, ConfigKey.AI_AFTER_HOUR_END, previous[1], actorEmail);
        restoreKey(configService, ConfigKey.AI_AFTER_HOUR_START, previous[0], actorEmail);
    }

    private static void restoreKey(SystemConfigService configService, ConfigKey key, String value, String actorEmail) {
        if (!value.equals(configService.getString(key))) {
            configService.update(key.getKey(), value, actorEmail);
        }
    }

    private static void writeDirectly(SystemConfigurationRepository configRepository, ConfigKey key, String value) {
        SystemConfiguration row = configRepository.findById(key.getKey()).orElseThrow();
        row.setConfigValue(value);
        configRepository.save(row);
    }
}
