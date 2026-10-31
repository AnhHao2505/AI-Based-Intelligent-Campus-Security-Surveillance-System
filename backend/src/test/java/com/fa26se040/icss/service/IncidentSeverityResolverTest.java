package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentTimeWindow;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.util.AfterHoursWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BR-SEV-01..03: luật đúng khung giờ → luật ANY → INCIDENT_SEVERITY_DEFAULT.
 * Bảng luật trong bộ nhớ = đúng 10 dòng seed V90; khung giờ thật qua AfterHoursWindow với cấu hình 22:00–06:00.
 */
@ExtendWith(MockitoExtension.class)
class IncidentSeverityResolverTest {

    private static final LocalDate DAY = LocalDate.of(2031, 3, 12);

    @Mock
    private IncidentSeverityRuleRepository ruleRepository;

    @Mock
    private SystemConfigService systemConfigService;

    private IncidentSeverityResolver resolver;
    private final List<IncidentSeverityRule> rules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seed("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.IN_HOURS, IncidentSeverity.HIGH);
        seed("UNKNOWN_PERSON", AreaLevel.HIGHLY_CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.CRITICAL);
        seed("INTRUSION", AreaLevel.PUBLIC, IncidentTimeWindow.AFTER_HOURS, IncidentSeverity.MEDIUM);
        seed("INTRUSION", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.AFTER_HOURS, IncidentSeverity.CRITICAL);
        seed("INTRUSION", AreaLevel.HIGHLY_CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.CRITICAL);
        seed("UNAUTHORIZED_ACCESS", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.HIGH);
        seed("UNAUTHORIZED_ACCESS", AreaLevel.HIGHLY_CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.CRITICAL);
        seed("ACCESS_OUT_OF_TIME", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.IN_HOURS, IncidentSeverity.MEDIUM);
        seed("ACCESS_OUT_OF_TIME", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.AFTER_HOURS, IncidentSeverity.HIGH);
        seed("ACCESS_OUT_OF_TIME", AreaLevel.HIGHLY_CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.CRITICAL);

        lenient().when(ruleRepository.findByEventTypeAndAreaLevelCodeAndTimeWindow(any(), any(), any()))
                .thenAnswer(inv -> rules.stream()
                        .filter(r -> r.getEventType().equals(inv.getArgument(0))
                                && r.getAreaLevelCode() == inv.getArgument(1)
                                && r.getTimeWindow() == inv.getArgument(2))
                        .findFirst());
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START)).thenReturn("22:00");
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END)).thenReturn("06:00");
        lenient().when(systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT)).thenReturn("MEDIUM");

        resolver = new IncidentSeverityResolver(ruleRepository, new AfterHoursWindow(systemConfigService), systemConfigService);
    }

    private void seed(String eventType, AreaLevel level, IncidentTimeWindow window, IncidentSeverity severity) {
        rules.add(IncidentSeverityRule.builder()
                .eventType(eventType).areaLevelCode(level).timeWindow(window).severity(severity).build());
    }

    private static OffsetDateTime vn(String hhmm) {
        return OffsetDateTime.of(DAY, LocalTime.parse(hhmm), ZoneOffset.ofHours(7));
    }

    @ParameterizedTest(name = "{0} / {1} lúc {2} -> {3}")
    @DisplayName("Mọi dòng seed V90: luật IN_HOURS lúc 10:00, AFTER_HOURS lúc 23:00, ANY ở cả hai giờ")
    @CsvSource({
            "UNKNOWN_PERSON, CONFIDENTIAL, 10:00, HIGH",
            "UNKNOWN_PERSON, HIGHLY_CONFIDENTIAL, 10:00, CRITICAL",
            "UNKNOWN_PERSON, HIGHLY_CONFIDENTIAL, 23:00, CRITICAL",
            "INTRUSION, PUBLIC, 23:00, MEDIUM",
            "INTRUSION, CONFIDENTIAL, 23:00, CRITICAL",
            "INTRUSION, HIGHLY_CONFIDENTIAL, 10:00, CRITICAL",
            "INTRUSION, HIGHLY_CONFIDENTIAL, 23:00, CRITICAL",
            "UNAUTHORIZED_ACCESS, CONFIDENTIAL, 10:00, HIGH",
            "UNAUTHORIZED_ACCESS, CONFIDENTIAL, 23:00, HIGH",
            "UNAUTHORIZED_ACCESS, HIGHLY_CONFIDENTIAL, 10:00, CRITICAL",
            "UNAUTHORIZED_ACCESS, HIGHLY_CONFIDENTIAL, 23:00, CRITICAL",
            "ACCESS_OUT_OF_TIME, CONFIDENTIAL, 10:00, MEDIUM",
            "ACCESS_OUT_OF_TIME, CONFIDENTIAL, 23:00, HIGH",
            "ACCESS_OUT_OF_TIME, HIGHLY_CONFIDENTIAL, 10:00, CRITICAL",
            "ACCESS_OUT_OF_TIME, HIGHLY_CONFIDENTIAL, 23:00, CRITICAL"
    })
    void everySeedRow(String eventType, AreaLevel level, String time, IncidentSeverity expected) {
        assertEquals(expected, resolver.resolve(eventType, level, vn(time)));
    }

    @ParameterizedTest(name = "INTRUSION / CONFIDENTIAL lúc {0} -> {1}")
    @DisplayName("Qua nửa đêm: 22:00, 02:00, 05:59 thuộc AFTER_HOURS; 06:00 và 21:59 là IN_HOURS, không có luật -> mặc định")
    @CsvSource({
            "22:00, CRITICAL",
            "02:00, CRITICAL",
            "05:59, CRITICAL",
            "06:00, MEDIUM",
            "21:59, MEDIUM"
    })
    void crossMidnight(String time, IncidentSeverity expected) {
        assertEquals(expected, resolver.resolve("INTRUSION", AreaLevel.CONFIDENTIAL, vn(time)));
    }

    @ParameterizedTest(name = "{0} / {1} lúc {2} không có luật -> INCIDENT_SEVERITY_DEFAULT")
    @DisplayName("Ô '—' của bảng (không seed) -> INCIDENT_SEVERITY_DEFAULT")
    @CsvSource({
            "UNKNOWN_PERSON, PUBLIC, 10:00",
            "UNKNOWN_PERSON, CONFIDENTIAL, 23:00",
            "INTRUSION, PUBLIC, 10:00",
            "UNAUTHORIZED_ACCESS, PUBLIC, 23:00",
            "ACCESS_OUT_OF_TIME, PUBLIC, 10:00",
            "SECURITY_ALERT, HIGHLY_CONFIDENTIAL, 10:00"
    })
    void missingRule_usesConfiguredDefault(String eventType, AreaLevel level, String time) {
        lenient().when(systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT)).thenReturn("HIGH");

        assertEquals(IncidentSeverity.HIGH, resolver.resolve(eventType, level, vn(time)));
    }

    @Test
    @DisplayName("Có cả luật đúng khung giờ và luật ANY -> lấy luật đúng khung giờ")
    void exactWindowWinsOverAny() {
        seed("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.ANY, IncidentSeverity.CRITICAL);

        assertEquals(IncidentSeverity.HIGH, resolver.resolve("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, vn("10:00")));
        assertEquals(IncidentSeverity.CRITICAL, resolver.resolve("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, vn("23:00")));
    }

    @Test
    @DisplayName("Khu không có cấp hoặc loại sự cố null -> mặc định, không tra bảng luật")
    void nullInputs_useDefault() {
        assertEquals(IncidentSeverity.MEDIUM, resolver.resolve("INTRUSION", null, vn("23:00")));
        assertEquals(IncidentSeverity.MEDIUM, resolver.resolve(null, AreaLevel.CONFIDENTIAL, vn("23:00")));
        verify(ruleRepository, never()).findByEventTypeAndAreaLevelCodeAndTimeWindow(any(), any(), any());
    }

    @Test
    @DisplayName("INCIDENT_SEVERITY_DEFAULT sai (sửa thẳng DB) -> mặc định của ConfigKey (MEDIUM), không ném lỗi")
    void invalidDefaultConfig_fallsBackToEnumDefault() {
        lenient().when(systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT)).thenReturn("URGENT");

        assertEquals(IncidentSeverity.MEDIUM, resolver.resolve("UNKNOWN_PERSON", AreaLevel.PUBLIC, vn("10:00")));
    }

    @Test
    @DisplayName("Admin sửa luật -> lần resolve sau dùng giá trị mới (đọc bảng mỗi lần gọi)")
    void ruleChange_appliesToNextCall() {
        assertEquals(IncidentSeverity.HIGH, resolver.resolve("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, vn("10:00")));

        rules.get(0).setSeverity(IncidentSeverity.CRITICAL);

        assertEquals(IncidentSeverity.CRITICAL, resolver.resolve("UNKNOWN_PERSON", AreaLevel.CONFIDENTIAL, vn("10:00")));
    }
}
