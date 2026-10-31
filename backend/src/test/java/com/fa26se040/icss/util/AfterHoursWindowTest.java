package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** BR-SEV-02: khung ngoài giờ [AI_AFTER_HOUR_START, AI_AFTER_HOUR_END), qua nửa đêm khi start > end, giờ Việt Nam. */
@ExtendWith(MockitoExtension.class)
class AfterHoursWindowTest {

    private static final LocalDate DAY = LocalDate.of(2031, 3, 12);
    private static final ZoneOffset VN = ZoneOffset.ofHours(7);

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AfterHoursWindow afterHoursWindow;

    private void window(String start, String end) {
        when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START)).thenReturn(start);
        when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END)).thenReturn(end);
    }

    private static OffsetDateTime vn(String hhmm) {
        return OffsetDateTime.of(DAY, LocalTime.parse(hhmm), VN);
    }

    @ParameterizedTest(name = "[{0}, {1}) chứa {2} = {3}")
    @DisplayName("isWithin: khung trong ngày, qua nửa đêm, rỗng; start tính, end không tính")
    @CsvSource({
            "08:00, 17:00, 08:00, true",
            "08:00, 17:00, 16:59, true",
            "08:00, 17:00, 17:00, false",
            "08:00, 17:00, 07:59, false",
            "22:00, 06:00, 22:00, true",
            "22:00, 06:00, 23:59, true",
            "22:00, 06:00, 00:00, true",
            "22:00, 06:00, 05:59, true",
            "22:00, 06:00, 06:00, false",
            "22:00, 06:00, 21:59, false",
            "22:00, 06:00, 12:00, false",
            "10:00, 10:00, 10:00, false",
            "10:00, 10:00, 03:00, false"
    })
    void isWithin_semantics(String start, String end, String t, boolean expected) {
        assertEquals(expected, AfterHoursWindow.isWithin(LocalTime.parse(t), LocalTime.parse(start), LocalTime.parse(end)));
    }

    @Test
    @DisplayName("isAfterHours đọc cấu hình: 23:30 và 02:00 trong khung 22:00–06:00, 06:00 và 10:00 ngoài khung")
    void isAfterHours_readsConfig_crossMidnight() {
        window("22:00", "06:00");

        assertTrue(afterHoursWindow.isAfterHours(vn("23:30")));
        assertTrue(afterHoursWindow.isAfterHours(vn("02:00")));
        assertFalse(afterHoursWindow.isAfterHours(vn("06:00")));
        assertFalse(afterHoursWindow.isAfterHours(vn("10:00")));
    }

    @Test
    @DisplayName("isAfterHours quy về giờ Việt Nam: 16:30 UTC = 23:30 VN là ngoài giờ")
    void isAfterHours_convertsToVietnamTime() {
        window("22:00", "06:00");

        assertTrue(afterHoursWindow.isAfterHours(OffsetDateTime.of(DAY, LocalTime.of(16, 30), ZoneOffset.UTC)));
        assertFalse(afterHoursWindow.isAfterHours(OffsetDateTime.of(DAY, LocalTime.of(3, 0), ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("Sửa cấu hình có hiệu lực ngay: lần gọi sau đọc giá trị mới")
    void isAfterHours_rereadsConfigEachCall() {
        when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START)).thenReturn("22:00", "20:00");
        when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END)).thenReturn("06:00", "06:00");

        assertFalse(afterHoursWindow.isAfterHours(vn("21:00")));
        assertTrue(afterHoursWindow.isAfterHours(vn("21:00")));
    }

    @Test
    @DisplayName("Giá trị cấu hình sai định dạng -> dùng mặc định của ConfigKey (22:00–06:00), không ném lỗi")
    void isAfterHours_invalidConfig_fallsBackToDefault() {
        window("25:99", "abc");

        assertTrue(afterHoursWindow.isAfterHours(vn("23:00")));
        assertFalse(afterHoursWindow.isAfterHours(vn("12:00")));
    }
}
