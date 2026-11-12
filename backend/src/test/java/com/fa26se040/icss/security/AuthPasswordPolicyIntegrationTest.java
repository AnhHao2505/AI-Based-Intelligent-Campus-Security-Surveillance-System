package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * BE-06 (V119): PUT /api/auth/reset-password kiểm mật khẩu mới — dài ít nhất PASSWORD_MIN_LENGTH (mặc định 8) và có
 * cả chữ lẫn số. Bị từ chối thì 400, mật khẩu không đổi, token chưa bị dùng (người dùng nhập lại được).
 */
class AuthPasswordPolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private JdbcTemplate jdbc;

    // Cùng bộ mock với AuthPasswordResetIntegrationTest để dùng chung Spring context
    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final List<UUID> createdIds = new ArrayList<>();
    private User account;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        account = userRepository.save(User.builder()
                .email("be06." + suffix + "@fpt.edu.vn")
                .userCode("BE06-" + suffix)
                .fullName("BE06 " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .password(passwordEncoder.encode("OldPass-1"))
                .build());
        createdIds.add(account.getId());
    }

    @AfterAll
    void cleanUp() {
        userRepository.findAllById(createdIds).forEach(u -> {
            passwordResetTokenRepository.findAll().stream()
                    .filter(t -> u.getEmail().equals(t.getEmail()))
                    .forEach(passwordResetTokenRepository::delete);
            u.setIsActive(false);
            userRepository.save(u);
        });
    }

    private PasswordResetToken token() {
        return passwordResetTokenRepository.save(PasswordResetToken.builder()
                .token(UUID.randomUUID().toString())
                .email(account.getEmail())
                .expiredAt(OffsetDateTime.now().plusMinutes(10))
                .isUsed(false)
                .build());
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(put("/api/auth/reset-password")
                        .with(request -> {
                            request.setRemoteAddr("10.252.0." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(254)));
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", token, "newPassword", newPassword))))
                .andReturn();
    }

    private String message(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("message").asText();
    }

    @Test
    @DisplayName("V119 có khoá PASSWORD_MIN_LENGTH = 8, min 6, max 64, nhóm SECURITY")
    void migrationSeedsConfig() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT config_value, data_type, min_value, max_value, config_group, editable FROM system_configurations WHERE config_key = 'PASSWORD_MIN_LENGTH'");
        assertThat(row.get("config_value")).isEqualTo("8");
        assertThat(row.get("data_type")).isEqualTo("INTEGER");
        assertThat(((Number) row.get("min_value")).intValue()).isEqualTo(6);
        assertThat(((Number) row.get("max_value")).intValue()).isEqualTo(64);
        assertThat(row.get("config_group")).isEqualTo("SECURITY");
        assertThat(row.get("editable")).isEqualTo(true);
        assertThat(systemConfigService.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).isEqualTo(8);
    }

    @Test
    @DisplayName("Mật khẩu ngắn / thiếu số / thiếu chữ -> 400 câu nêu 8 ký tự, mật khẩu cũ giữ nguyên, token chưa dùng")
    void weakPasswords_rejected_tokenStillUsable() throws Exception {
        PasswordResetToken t = token();
        for (String weak : List.of("abc12", "abcdefgh", "12345678", "1234567a".substring(1))) {
            MvcResult r = reset(t.getToken(), weak);
            assertThat(r.getResponse().getStatus()).as(weak).isEqualTo(400);
            assertThat(message(r)).as(weak).isEqualTo("Mật khẩu mới phải có ít nhất 8 ký tự, gồm cả chữ cái và chữ số.");
        }
        assertThat(passwordEncoder.matches("OldPass-1", userRepository.findById(account.getId()).orElseThrow().getPassword())).isTrue();
        assertThat(passwordResetTokenRepository.findByToken(t.getToken()).orElseThrow().getIsUsed()).isFalse();

        // Cùng token, mật khẩu đạt -> 200
        MvcResult ok = reset(t.getToken(), "abcd1234");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(passwordEncoder.matches("abcd1234", userRepository.findById(account.getId()).orElseThrow().getPassword())).isTrue();
        assertThat(passwordResetTokenRepository.findByToken(t.getToken()).orElseThrow().getIsUsed()).isTrue();
    }

    @Test
    @DisplayName("Token sai + mật khẩu yếu -> vẫn 401 'Liên kết không hợp lệ' (không lộ quy tắc mật khẩu cho token giả)")
    void invalidToken_weakPassword_stillLinkError() throws Exception {
        MvcResult r = reset(UUID.randomUUID().toString(), "abc");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(message(r)).isEqualTo("Liên kết không hợp lệ hoặc đã hết hạn.");
    }
}
