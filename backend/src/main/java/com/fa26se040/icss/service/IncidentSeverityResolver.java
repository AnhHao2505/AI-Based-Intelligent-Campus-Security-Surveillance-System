package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentTimeWindow;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.util.AfterHoursWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Tính mức độ sự cố lúc ingest (BR-SEV-01..03).
 * Thứ tự khớp: luật đúng khung giờ (IN_HOURS / AFTER_HOURS) → luật ANY → INCIDENT_SEVERITY_DEFAULT + log WARN.
 * Đọc luật và cấu hình mỗi lần gọi nên admin sửa có hiệu lực ngay với sự cố mới, không hồi tố sự cố cũ (BR-SEV-04).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentSeverityResolver {

    private final IncidentSeverityRuleRepository ruleRepository;
    private final AfterHoursWindow afterHoursWindow;
    private final SystemConfigService systemConfigService;

    public IncidentSeverity resolve(String eventType, AreaLevel areaLevel, OffsetDateTime at) {
        IncidentTimeWindow window = afterHoursWindow.isAfterHours(at)
                ? IncidentTimeWindow.AFTER_HOURS
                : IncidentTimeWindow.IN_HOURS;

        if (eventType != null && areaLevel != null) {
            Optional<IncidentSeverityRule> rule = ruleRepository
                    .findByEventTypeAndAreaLevelCodeAndTimeWindow(eventType, areaLevel, window)
                    .or(() -> ruleRepository.findByEventTypeAndAreaLevelCodeAndTimeWindow(
                            eventType, areaLevel, IncidentTimeWindow.ANY));
            if (rule.isPresent()) {
                return rule.get().getSeverity();
            }
        }

        IncidentSeverity fallback = defaultSeverity();
        log.warn("Không có luật severity cho [{} / {} / {}], dùng {} = {}",
                eventType, areaLevel, window, ConfigKey.INCIDENT_SEVERITY_DEFAULT.getKey(), fallback);
        return fallback;
    }

    /** INCIDENT_SEVERITY_DEFAULT; giá trị sửa thẳng trong DB không phải MEDIUM/HIGH/CRITICAL -> WARN + mặc định của enum. */
    IncidentSeverity defaultSeverity() {
        String raw = systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT);
        try {
            return IncidentSeverity.valueOf(raw.trim());
        } catch (RuntimeException e) {
            log.warn("Giá trị [{}] của cấu hình [{}] không hợp lệ. Dùng mặc định: {}",
                    raw, ConfigKey.INCIDENT_SEVERITY_DEFAULT.getKey(), ConfigKey.INCIDENT_SEVERITY_DEFAULT.getDefaultValue());
            return IncidentSeverity.valueOf(ConfigKey.INCIDENT_SEVERITY_DEFAULT.getDefaultValue());
        }
    }
}
