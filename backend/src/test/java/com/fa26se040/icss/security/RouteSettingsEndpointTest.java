package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-MAP-2 (V123, MM-05): GET /api/system-configurations/route-settings — tham số ước tính thời gian lộ trình
 * (WALKING_SPEED_MPS, FLOOR_CHANGE_SECONDS) cho mọi tài khoản đã đăng nhập; đổi cấu hình có hiệu lực ngay.
 */
class RouteSettingsEndpointTest extends AbstractIntegrationTest {

    private static final String URL = "/api/system-configurations/route-settings";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private SystemConfigService systemConfigService;

    private final Map<Role, User> users = new EnumMap<>(Role.class);
    private String originalSpeed;
    private String originalFloorSeconds;

    private User user(Role role) {
        return users.computeIfAbsent(role, r -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            return userRepository.save(User.builder()
                    .userCode("RTE-" + r.name().substring(0, 3) + "-" + suffix)
                    .email("route.settings." + r.name().toLowerCase() + "." + suffix + "@fpt.edu.vn")
                    .fullName("Route Settings " + r.name())
                    .role(r)
                    .accessLevel(3)
                    .isActive(true)
                    .build());
        });
    }

    private String bearer(Role role) {
        return "Bearer " + jwtTokenProvider.generateToken(user(role));
    }

    @BeforeEach
    void rememberConfig() {
        originalSpeed = systemConfigService.getString(ConfigKey.WALKING_SPEED_MPS);
        originalFloorSeconds = systemConfigService.getString(ConfigKey.FLOOR_CHANGE_SECONDS);
    }

    @AfterEach
    void restoreConfig() {
        String admin = user(Role.ADMIN).getEmail();
        if (!originalSpeed.equals(systemConfigService.getString(ConfigKey.WALKING_SPEED_MPS))) {
            systemConfigService.update(ConfigKey.WALKING_SPEED_MPS.name(), originalSpeed, admin);
        }
        if (!originalFloorSeconds.equals(systemConfigService.getString(ConfigKey.FLOOR_CHANGE_SECONDS))) {
            systemConfigService.update(ConfigKey.FLOOR_CHANGE_SECONDS.name(), originalFloorSeconds, admin);
        }
        for (User u : users.values()) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        users.clear();
    }

    @Test
    @DisplayName("Không token → 401")
    void noToken_unauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("V123 seed đúng mặc định; GUARD (và mọi vai trò) → 200, đúng 2 giá trị, không lộ trường khác")
    void guardReadsDefaults() throws Exception {
        systemConfigService.update(ConfigKey.WALKING_SPEED_MPS.name(), "1.45", user(Role.ADMIN).getEmail());
        systemConfigService.update(ConfigKey.FLOOR_CHANGE_SECONDS.name(), "14", user(Role.ADMIN).getEmail());
        mockMvc.perform(get(URL).header("Authorization", bearer(Role.GUARD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walkingSpeedMps").value(1.45))
                .andExpect(jsonPath("$.data.floorChangeSeconds").value(14))
                .andExpect(jsonPath("$.data.configKey").doesNotExist());
        for (Role role : Role.values()) {
            mockMvc.perform(get(URL).header("Authorization", bearer(role))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Đổi WALKING_SPEED_MPS / FLOOR_CHANGE_SECONDS → phản hồi đổi ngay, không cần khởi động lại")
    void configChange_reflectedImmediately() throws Exception {
        String admin = user(Role.ADMIN).getEmail();
        systemConfigService.update(ConfigKey.WALKING_SPEED_MPS.name(), "1.2", admin);
        systemConfigService.update(ConfigKey.FLOOR_CHANGE_SECONDS.name(), "30", admin);
        mockMvc.perform(get(URL).header("Authorization", bearer(Role.GUARD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walkingSpeedMps").value(1.2))
                .andExpect(jsonPath("$.data.floorChangeSeconds").value(30));
    }

    @Test
    @DisplayName("Khoá có metadata đúng V123 (DECIMAL 0.5–3.0, INTEGER 0–120, nhóm SECURITY, sửa được)")
    void migrationMetadata() {
        var all = systemConfigService.getAll();
        var speed = all.stream().filter(c -> c.configKey().equals("WALKING_SPEED_MPS")).findFirst().orElseThrow();
        var floor = all.stream().filter(c -> c.configKey().equals("FLOOR_CHANGE_SECONDS")).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(speed.dataType()).isEqualTo("DECIMAL");
        org.assertj.core.api.Assertions.assertThat(speed.minValue()).isEqualByComparingTo("0.5");
        org.assertj.core.api.Assertions.assertThat(speed.maxValue()).isEqualByComparingTo("3.0");
        org.assertj.core.api.Assertions.assertThat(speed.configGroup()).isEqualTo("SECURITY");
        org.assertj.core.api.Assertions.assertThat(speed.editable()).isTrue();
        org.assertj.core.api.Assertions.assertThat(floor.dataType()).isEqualTo("INTEGER");
        org.assertj.core.api.Assertions.assertThat(floor.minValue()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(floor.maxValue()).isEqualByComparingTo("120");
    }
}
