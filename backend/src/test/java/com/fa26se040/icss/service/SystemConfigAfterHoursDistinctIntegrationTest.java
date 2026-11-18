package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #262 trên DB test thật, qua JWT + @PreAuthorize + @Valid: ADMIN PATCH làm START = END -> 400, không ghi gì.
 * Chỉ gửi request bị từ chối nên không đổi khung ban đêm của DB test dùng chung. Luồng thành công và MQTT kiểm ở
 * SystemConfigAfterHoursDistinctTest (mock), EV-N8 thật ở NightWindowEventScheduleIntegrationTest.
 */
class SystemConfigAfterHoursDistinctIntegrationTest extends AbstractIntegrationTest {

    private static final String START = ConfigKey.AI_AFTER_HOUR_START.getKey();
    private static final String END = ConfigKey.AI_AFTER_HOUR_END.getKey();
    private static final String SAME_MESSAGE = "Giờ bắt đầu và kết thúc ngoài giờ không được trùng nhau!";
    private static final String REASON = "Điều chỉnh khung giờ ngoài giờ hành chính";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private SystemConfigurationRepository configRepository;
    @Autowired private SystemConfigurationChangeLogRepository changeLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private User admin;

    @BeforeAll
    void createAdmin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .userCode("AHD-ADM-" + suffix)
                .email("after.hours.distinct." + suffix + "@fpt.edu.vn")
                .fullName("Admin kiểm thử giờ ngoài giờ")
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());
    }

    /** Không để ADMIN kiểm thử còn hoạt động trên DB test dùng chung (thông báo "mọi ADMIN" của module khác). */
    @AfterAll
    void deactivateAdmin() {
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    private ResultActions patchConfig(String key, String value) throws Exception {
        return mockMvc.perform(patch("/api/system-configurations/{configKey}", key)
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"configValue\":\"" + value + "\",\"reason\":\"" + REASON + "\"}"));
    }

    private String dbValue(String key) {
        return configRepository.findById(key).orElseThrow().getConfigValue().trim();
    }

    private long changeLogs(String key) {
        return changeLogRepository.findByConfigKeyOrderByChangedAtDesc(key, Pageable.unpaged()).getTotalElements();
    }

    private long audits(String key) {
        Specification<AuditLog> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("targetType"), AuditTargetType.SYSTEM_CONFIG),
                cb.equal(root.get("targetId"), key));
        return auditLogRepository.count(spec);
    }

    private void assertNothingChanged(String start, String end, long[] logsBefore, long[] auditsBefore) {
        assertEquals(start, dbValue(START), "DB START giữ nguyên");
        assertEquals(end, dbValue(END), "DB END giữ nguyên");
        assertEquals(start, systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START), "cache START giữ nguyên");
        assertEquals(end, systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END), "cache END giữ nguyên");
        assertEquals(logsBefore[0], changeLogs(START), "không thêm change log START");
        assertEquals(logsBefore[1], changeLogs(END), "không thêm change log END");
        assertEquals(auditsBefore[0], audits(START), "không thêm audit START");
        assertEquals(auditsBefore[1], audits(END), "không thêm audit END");
    }

    @Test
    @DisplayName("#262 (DB thật): ADMIN PATCH START = END hiện hành -> 400 đúng câu FE; END = START có khoảng trắng -> 400; DB, cache, change log, audit không đổi")
    void patchSameStartEnd_rejected_nothingChanged() throws Exception {
        String start = dbValue(START);
        String end = dbValue(END);
        long[] logsBefore = {changeLogs(START), changeLogs(END)};
        long[] auditsBefore = {audits(START), audits(END)};

        patchConfig(START, end)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpCode").value(400))
                .andExpect(jsonPath("$.message").value(SAME_MESSAGE));
        patchConfig(END, "  " + start + " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SAME_MESSAGE));

        assertNothingChanged(start, end, logsBefore, auditsBefore);
    }
}
