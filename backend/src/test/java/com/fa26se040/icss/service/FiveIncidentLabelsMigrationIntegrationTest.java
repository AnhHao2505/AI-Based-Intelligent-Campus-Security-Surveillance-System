package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.util.AfterHoursWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V95 (quyết định 03/11, 5 nhãn sự cố) trên DB test thật sau Flyway migrate: bảng luật severity có đúng 11 dòng,
 * không còn tên cũ, và resolver với bảng luật thật cho đúng mức ở các ô đổi theo quyết định.
 * Chỉ đọc, không sửa dữ liệu.
 */
class FiveIncidentLabelsMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IncidentSeverityRuleRepository severityRuleRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    @DisplayName("V95 đã áp: flyway_schema_history có V95 thành công")
    void v95Applied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '95' AND success = TRUE", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("Sau V95: đúng 11 luật severity theo bảng quyết định 03/11")
    void exactlyElevenRules() {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT event_type || '/' || area_level_code || '/' || time_window || '=' || severity FROM incident_severity_rules",
                String.class);

        assertThat(rows).containsExactlyInAnyOrder(
                "UNKNOWN_PERSON/CONFIDENTIAL/IN_HOURS=HIGH",
                "UNKNOWN_PERSON/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "PERSON_INTRUSION/PUBLIC/AFTER_HOURS=MEDIUM",
                "PERSON_INTRUSION/CONFIDENTIAL/AFTER_HOURS=CRITICAL",
                "PERSON_INTRUSION/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "UNAUTHORIZED_PERSON/CONFIDENTIAL/ANY=HIGH",
                "UNAUTHORIZED_PERSON/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "SUSPICIOUS_MOVING_OBJECT/PUBLIC/AFTER_HOURS=MEDIUM",
                "SUSPICIOUS_MOVING_OBJECT/CONFIDENTIAL/IN_HOURS=HIGH",
                "SUSPICIOUS_MOVING_OBJECT/CONFIDENTIAL/AFTER_HOURS=CRITICAL",
                "SUSPICIOUS_MOVING_OBJECT/HIGHLY_CONFIDENTIAL/ANY=CRITICAL");
    }

    @Test
    @DisplayName("Sau V95: không còn ACCESS_OUT_OF_TIME / INTRUSION / UNAUTHORIZED_ACCESS trong incident_severity_rules")
    void noLegacyRuleNames() {
        Integer legacy = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident_severity_rules WHERE event_type IN ('ACCESS_OUT_OF_TIME', 'INTRUSION', 'UNAUTHORIZED_ACCESS')",
                Integer.class);
        assertThat(legacy).isZero();
    }

    @Test
    @DisplayName("Resolver với bảng luật thật: UNAUTHORIZED_PERSON + CONFIDENTIAL trong giờ = HIGH; SUSPICIOUS_MOVING_OBJECT + CONFIDENTIAL ngoài giờ = CRITICAL")
    void resolverWithRealRules() {
        AfterHoursWindow inHours = mock(AfterHoursWindow.class);
        AfterHoursWindow afterHours = mock(AfterHoursWindow.class);
        when(inHours.isAfterHours(any())).thenReturn(false);
        when(afterHours.isAfterHours(any())).thenReturn(true);
        IncidentSeverityResolver day = new IncidentSeverityResolver(severityRuleRepository, inHours, systemConfigService);
        IncidentSeverityResolver night = new IncidentSeverityResolver(severityRuleRepository, afterHours, systemConfigService);
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(day.resolve("UNAUTHORIZED_PERSON", AreaLevel.CONFIDENTIAL, at)).isEqualTo(IncidentSeverity.HIGH);
        // Sai khung giờ từ AI bản cũ: chuẩn hoá trước khi tra luật -> cùng mức với vào trái phép
        assertThat(day.resolve(SecurityIncidentService.normalizeEventType("ACCESS_OUT_OF_TIME"), AreaLevel.CONFIDENTIAL, at))
                .isEqualTo(IncidentSeverity.HIGH);
        assertThat(night.resolve("SUSPICIOUS_MOVING_OBJECT", AreaLevel.CONFIDENTIAL, at)).isEqualTo(IncidentSeverity.CRITICAL);
    }
}
