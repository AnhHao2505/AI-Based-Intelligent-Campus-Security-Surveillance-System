package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KT-01: GET /api/auth/password-policy cho form đặt lại mật khẩu — không cần token, trả minLength theo
 * PASSWORD_MIN_LENGTH hiện hành (sửa cấu hình có hiệu lực ngay) và requireLetterAndDigit = true.
 */
class PasswordPolicyEndpointIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // Cùng bộ mock với các test auth khác để dùng chung Spring context
    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private User admin;
    private String originalMinLength;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .email("kt01." + suffix + "@fpt.edu.vn")
                .userCode("KT01-" + suffix)
                .fullName("KT01 " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .password(passwordEncoder.encode("OldPass-1"))
                .build());
        originalMinLength = systemConfigService.getString(ConfigKey.PASSWORD_MIN_LENGTH);
    }

    @AfterEach
    void tearDown() {
        if (!originalMinLength.equals(systemConfigService.getString(ConfigKey.PASSWORD_MIN_LENGTH))) {
            systemConfigService.update(ConfigKey.PASSWORD_MIN_LENGTH.name(), originalMinLength, admin.getEmail());
        }
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    @Test
    @DisplayName("Không gửi token -> 200, minLength = PASSWORD_MIN_LENGTH hiện hành, requireLetterAndDigit = true")
    void noToken_returnsCurrentPolicy() throws Exception {
        int current = Integer.parseInt(originalMinLength);
        mockMvc.perform(get("/api/auth/password-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minLength").value(current))
                .andExpect(jsonPath("$.data.requireLetterAndDigit").value(true));
    }

    @Test
    @DisplayName("Đổi PASSWORD_MIN_LENGTH 8 -> 12 -> phản hồi đổi theo ngay, không cần khởi động lại")
    void configChange_reflectedImmediately() throws Exception {
        systemConfigService.update(ConfigKey.PASSWORD_MIN_LENGTH.name(), "8", admin.getEmail());
        mockMvc.perform(get("/api/auth/password-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minLength").value(8));

        systemConfigService.update(ConfigKey.PASSWORD_MIN_LENGTH.name(), "12", admin.getEmail());
        mockMvc.perform(get("/api/auth/password-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minLength").value(12))
                .andExpect(jsonPath("$.data.requireLetterAndDigit").value(true));
    }
}
