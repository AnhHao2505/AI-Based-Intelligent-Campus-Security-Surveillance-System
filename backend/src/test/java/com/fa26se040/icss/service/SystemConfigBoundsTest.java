package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N2 (đợt kiểm 12/11): 4 khoá EVENT_MODE_* kẹp theo min_value / max_value ở DB, không còn khoảng viết cứng trong AreaService.
 */
class SystemConfigBoundsTest {

    private static SystemConfiguration cfg(ConfigKey key, String value, Integer min, Integer max) {
        return SystemConfiguration.builder()
                .configKey(key.getKey())
                .configValue(value)
                .dataType("INTEGER")
                .minValue(min != null ? BigDecimal.valueOf(min) : null)
                .maxValue(max != null ? BigDecimal.valueOf(max) : null)
                .build();
    }

    private static SystemConfigService serviceWith(SystemConfiguration... configs) {
        SystemConfigurationRepository repo = mock(SystemConfigurationRepository.class);
        when(repo.findAll()).thenReturn(List.of(configs));
        SystemConfigService service = new SystemConfigService(repo, mock(SystemConfigurationChangeLogRepository.class),
                mock(UserRepository.class), null, null, null);
        service.refreshCache();
        return service;
    }

    private static AreaService areaServiceUsing(SystemConfigService configService) {
        AreaService areaService = Mockito.mock(AreaService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(areaService, "systemConfigService", configService);
        return areaService;
    }

    @Test
    @DisplayName("Trong khoảng DB (kể cả biên) -> giữ giá trị; khoảng lấy từ DB, không phải số cũ trong code")
    void withinDbBounds_returnsValue() {
        // Khoảng DB khác số viết cứng cũ (1–240, 0–240, 1–180, 0–1440): 300 > 240 cũ nhưng nằm trong DB 1–480
        SystemConfigService s = serviceWith(
                cfg(ConfigKey.EVENT_MODE_MIN_MINUTES, "300", 1, 480),
                cfg(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES, "0", 0, 240),
                cfg(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS, "180", 1, 180),
                cfg(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES, "1440", 0, 1440));
        AreaService a = areaServiceUsing(s);
        assertEquals(300, a.getEventModeMinMinutes());
        assertEquals(0, a.getEventModeExpiryReminderMinutes());
        assertEquals(180, a.getEventModeScheduleMaxLeadDays());
        assertEquals(1440, a.getEventModeScheduleReminderMinutes());
    }

    @Test
    @DisplayName("Ngoài khoảng DB (sửa thẳng trong DB) -> mặc định của ConfigKey")
    void outsideDbBounds_returnsConfigKeyDefault() {
        // 200 nằm trong 1–240 cũ nhưng ngoài khoảng DB 1–120 -> phải về mặc định
        SystemConfigService s = serviceWith(
                cfg(ConfigKey.EVENT_MODE_MIN_MINUTES, "200", 1, 120),
                cfg(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES, "-1", 0, 240),
                cfg(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS, "0", 1, 180),
                cfg(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES, "1441", 0, 1440));
        AreaService a = areaServiceUsing(s);
        assertEquals(Integer.parseInt(ConfigKey.EVENT_MODE_MIN_MINUTES.getDefaultValue()), a.getEventModeMinMinutes());
        assertEquals(Integer.parseInt(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.getDefaultValue()), a.getEventModeExpiryReminderMinutes());
        assertEquals(Integer.parseInt(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS.getDefaultValue()), a.getEventModeScheduleMaxLeadDays());
        assertEquals(Integer.parseInt(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES.getDefaultValue()), a.getEventModeScheduleReminderMinutes());
    }

    @Test
    @DisplayName("Thiếu dòng / sai kiểu -> mặc định (như getInt); không khai báo min / max -> giữ giá trị")
    void missingOrUnbounded() {
        SystemConfigService s = serviceWith(cfg(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS, "abc", 1, 180),
                cfg(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES, "99999", null, null));
        assertEquals(15, s.getIntWithinBounds(ConfigKey.EVENT_MODE_MIN_MINUTES), "thiếu dòng -> mặc định 15");
        assertEquals(30, s.getIntWithinBounds(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS), "sai kiểu -> mặc định 30");
        assertEquals(99999, s.getIntWithinBounds(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES), "không có min / max -> giữ");
    }
}
