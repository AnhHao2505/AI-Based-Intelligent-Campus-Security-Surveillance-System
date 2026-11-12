package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LT-06 / BR-224: chỉ ADMIN đọc danh sách cấu hình hệ thống. FM chỉ đọc khung ban đêm (form lịch sự kiện,
 * form gán nhân sự chỉ định); mọi tài khoản vẫn đọc được /reason-limits.
 */
class SystemConfigAuthorizationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    private final Map<Role, String> tokens = new EnumMap<>(Role.class);

    private String bearer(Role role) {
        return tokens.computeIfAbsent(role, r -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User user = userRepository.save(User.builder()
                    .userCode("CFG-" + r.name().substring(0, 3) + "-" + suffix)
                    .email("cfg.auth." + r.name().toLowerCase() + "." + suffix + "@fpt.edu.vn")
                    .fullName("Config Auth " + r.name())
                    .role(r)
                    .accessLevel(3)
                    .isActive(true)
                    .build());
            return "Bearer " + jwtTokenProvider.generateToken(user);
        });
    }

    @Test
    @DisplayName("GET /api/system-configurations: ADMIN 200; FM, GUARD, NORMAL_USER 403")
    void listConfigurations_adminOnly() throws Exception {
        mockMvc.perform(get("/api/system-configurations").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        for (Role role : List.of(Role.FACILITY_MANAGER, Role.GUARD, Role.NORMAL_USER)) {
            mockMvc.perform(get("/api/system-configurations").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.httpCode").value(403));
        }
    }

    @Test
    @DisplayName("GET /after-hours-window: ADMIN, FM nhận đúng 2 giá trị đang hiệu lực; GUARD, NORMAL_USER 403")
    void afterHoursWindow_adminAndFm() throws Exception {
        String start = systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START);
        String end = systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END);
        for (Role role : List.of(Role.ADMIN, Role.FACILITY_MANAGER)) {
            mockMvc.perform(get("/api/system-configurations/after-hours-window").header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.start").value(start))
                    .andExpect(jsonPath("$.data.end").value(end))
                    .andExpect(jsonPath("$.data.configKey").doesNotExist());
        }
        for (Role role : List.of(Role.GUARD, Role.NORMAL_USER)) {
            mockMvc.perform(get("/api/system-configurations/after-hours-window").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/system-configurations/after-hours-window")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /reason-limits vẫn mở cho mọi tài khoản đăng nhập")
    void reasonLimits_stillOpenForAllRoles() throws Exception {
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/system-configurations/reason-limits").header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.minLength").isNumber());
        }
    }
}
