package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.AuthService;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fa26se040.icss.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PR-AUDIT (B3): audit module AUTH qua HTTP thật. LOGIN_FAILED ghi trong transaction riêng nên vẫn còn dù request trả
 * 401; lý do nội bộ đúng từng trường hợp; body 401 ra ngoài vẫn giống hệt nhau (BR-AU-32, không phá test PR-AU).
 * Không ghi mật khẩu, token, link. Cùng bộ mock với AuthLoginIntegrationTest để dùng chung Spring context.
 */
class AuthAuditIntegrationTest extends AbstractIntegrationTest {

    private static final String UA = "PR-AUDIT-Auth/1.0";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockBean private NotificationService notificationService;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String password = "AA-" + UUID.randomUUID();
    private final List<UUID> createdIds = new ArrayList<>();
    private User admin;

    @BeforeAll
    void setUpAdmin() {
        admin = newUser("adm", Role.ADMIN);
    }

    @AfterAll
    void cleanUp() {
        for (UUID id : createdIds) {
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", id);
        }
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
                .email("aa." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("AA-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("AA " + tag + " " + suffix)
                .password(passwordEncoder.encode(password))
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
        createdIds.add(u.getId());
        return u;
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10.60." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static MockHttpServletRequestBuilder origin(MockHttpServletRequestBuilder b, String ip) {
        return b.header("User-Agent", UA).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private MvcResult login(String email, String pwd, String ip) throws Exception {
        return mockMvc.perform(origin(post("/api/auth/login"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", pwd))))
                .andReturn();
    }

    private String bodyWithoutTimestamp(MvcResult r) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        node.remove("timestamp");
        return objectMapper.writeValueAsString(node);
    }

    /** Dòng AUTH theo action, lọc theo e-mail trong snapshot (e-mail duy nhất mỗi test). */
    private List<Map<String, Object>> authAudits(String action, String email) {
        return jdbc.queryForList("SELECT target_id, subject_user_id, changed_by, actor_type, actor_source, ip_address, "
                + "user_agent, new_value::text AS new_json, new_value ->> 'failureReason' AS reason, "
                + "new_value ->> 'method' AS method FROM audit_logs WHERE target_type = 'AUTH' AND action = ? "
                + "AND new_value ->> 'email' = ?", action, email);
    }

    private static void assertNoSensitive(Map<String, Object> row) {
        String json = (String) row.get("new_json");
        assertThat(json).doesNotContain("password").doesNotContain("token").doesNotContain("http");
    }

    private static String unknownEmail() {
        return "aa.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn";
    }

    @Test
    @DisplayName("LOGIN_SUCCESS: actor = chính tài khoản, phương thức PASSWORD, có IP + user agent, không có mật khẩu / token")
    void loginSuccess_recorded() throws Exception {
        User user = newUser("ok", Role.NORMAL_USER);
        String ip = randomIp();
        assertThat(login(user.getEmail(), password, ip).getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> rows = authAudits("LOGIN_SUCCESS", user.getEmail());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("target_id")).isEqualTo(user.getId().toString());
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("changed_by")).isEqualTo(user.getId());
        assertThat(row.get("method")).isEqualTo("PASSWORD");
        assertThat(row.get("ip_address")).isEqualTo(ip);
        assertThat(row.get("user_agent")).isEqualTo(UA);
        assertNoSensitive(row);
    }

    @Test
    @DisplayName("LOGIN_FAILED: ghi dù trả 401, lý do nội bộ đúng (UNKNOWN_ACCOUNT / BAD_PASSWORD / DEACTIVATED / DELETED / LOCKED), body ra ngoài giống hệt")
    void loginFailed_recordedWithRealReason_bodyUnchanged() throws Exception {
        String unknown = unknownEmail();
        User wrong = newUser("bad", Role.NORMAL_USER);
        User off = newUser("off", Role.NORMAL_USER);
        off.setIsActive(false);
        userRepository.save(off);
        User deleted = newUser("del", Role.NORMAL_USER);
        userService.softDelete(deleted.getId(), admin.getEmail());
        User locked = newUser("lck", Role.NORMAL_USER);
        int max = systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        String lockIp = randomIp();
        for (int i = 0; i < max; i++) {
            assertThat(login(locked.getEmail(), password + "-x" + i, lockIp).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult rUnknown = login(unknown, password, randomIp());
        MvcResult rWrong = login(wrong.getEmail(), password + "x", randomIp());
        MvcResult rOff = login(off.getEmail(), password, randomIp());
        MvcResult rDeleted = login(deleted.getEmail(), password, randomIp());
        MvcResult rLocked = login(locked.getEmail(), password, randomIp());

        String expected = bodyWithoutTimestamp(rUnknown);
        assertThat(objectMapper.readTree(expected).path("message").asText()).isEqualTo(AuthService.LOGIN_FAILED_MESSAGE);
        for (MvcResult r : new MvcResult[]{rWrong, rOff, rDeleted, rLocked}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(bodyWithoutTimestamp(r)).isEqualTo(expected);
        }

        Map<String, Object> unknownRow = authAudits("LOGIN_FAILED", unknown).get(0);
        assertThat(unknownRow.get("reason")).isEqualTo("UNKNOWN_ACCOUNT");
        assertThat(unknownRow.get("target_id")).isEqualTo(unknown);
        assertThat(unknownRow.get("subject_user_id")).isNull();
        assertThat(unknownRow.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(unknownRow.get("actor_source")).isEqualTo("AUTH_LOGIN");
        assertThat(unknownRow.get("ip_address")).isNotNull();

        assertThat(authAudits("LOGIN_FAILED", wrong.getEmail())).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("reason")).isEqualTo("BAD_PASSWORD");
                    assertThat(row.get("subject_user_id")).isEqualTo(wrong.getId());
                    assertNoSensitive(row);
                });
        assertThat(authAudits("LOGIN_FAILED", off.getEmail())).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("DEACTIVATED"));
        assertThat(authAudits("LOGIN_FAILED", deleted.getEmail())).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("reason")).isEqualTo("DELETED");
                    assertThat(row.get("subject_user_id")).isEqualTo(deleted.getId());
                });
        List<Map<String, Object>> lockedRows = authAudits("LOGIN_FAILED", locked.getEmail());
        assertThat(lockedRows).hasSize(max + 1);
        assertThat(lockedRows.stream().filter(r -> "BAD_PASSWORD".equals(r.get("reason")))).hasSize(max);
        assertThat(lockedRows.stream().filter(r -> "LOCKED".equals(r.get("reason")))).hasSize(1);
    }

    @Test
    @DisplayName("ACCOUNT_LOCKED: đúng 1 dòng khi bắt đầu đợt khoá (tài khoản có thật và e-mail không có tài khoản), kèm số phút khoá")
    void accountLocked_recordedOncePerLock() throws Exception {
        User user = newUser("lk", Role.NORMAL_USER);
        String unknown = unknownEmail();
        int max = systemConfigService.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        String ip = randomIp();
        for (int i = 0; i < max + 2; i++) {
            login(user.getEmail(), password + "-w" + i, ip);
            login(unknown, password + "-w" + i, ip);
        }
        int lockMinutes = systemConfigService.getInt(ConfigKey.LOGIN_LOCK_MINUTES);
        assertThat(authAudits("ACCOUNT_LOCKED", user.getEmail())).singleElement().satisfies(row -> {
            assertThat(row.get("subject_user_id")).isEqualTo(user.getId());
            assertThat((String) row.get("new_json")).contains("\"lockMinutes\": " + lockMinutes);
        });
        assertThat(authAudits("ACCOUNT_LOCKED", unknown)).singleElement()
                .satisfies(row -> assertThat(row.get("subject_user_id")).isNull());
    }

    private static GoogleIdToken.Payload payload(String email) {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setEmail(email);
        return p;
    }

    @Test
    @DisplayName("Google: chưa đăng ký -> LOGIN_FAILED GOOGLE_NOT_REGISTERED; thành công -> LOGIN_SUCCESS phương thức GOOGLE")
    void googleLogin_recorded() throws Exception {
        String unregistered = unknownEmail();
        User active = newUser("g", Role.NORMAL_USER);
        when(googleTokenVerifier.verify("aa-unreg")).thenReturn(payload(unregistered));
        when(googleTokenVerifier.verify("aa-ok")).thenReturn(payload(active.getEmail()));

        mockMvc.perform(origin(post("/api/auth/google"), randomIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"aa-unreg\"}")).andReturn();
        mockMvc.perform(origin(post("/api/auth/google"), randomIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"aa-ok\"}")).andReturn();

        assertThat(authAudits("LOGIN_FAILED", unregistered)).singleElement().satisfies(row -> {
            assertThat(row.get("reason")).isEqualTo("GOOGLE_NOT_REGISTERED");
            assertThat(row.get("method")).isEqualTo("GOOGLE");
        });
        assertThat(authAudits("LOGIN_SUCCESS", active.getEmail())).singleElement()
                .satisfies(row -> assertThat(row.get("method")).isEqualTo("GOOGLE"));
    }

    @Test
    @DisplayName("Đặt lại mật khẩu: REQUESTED chỉ khi tài khoản tồn tại (phản hồi vẫn chung), COMPLETED khi đổi xong; không ghi token / link")
    void passwordReset_recorded() throws Exception {
        User user = newUser("rs", Role.NORMAL_USER);
        String unknown = unknownEmail();
        String ip = randomIp();
        MvcResult forUser = mockMvc.perform(origin(post("/api/auth/send-reset-link"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\"}")).andReturn();
        MvcResult forUnknown = mockMvc.perform(origin(post("/api/auth/send-reset-link"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + unknown + "\"}")).andReturn();
        assertThat(bodyWithoutTimestamp(forUnknown)).isEqualTo(bodyWithoutTimestamp(forUser));

        assertThat(authAudits("PASSWORD_RESET_REQUESTED", user.getEmail())).singleElement().satisfies(row -> {
            assertThat(row.get("subject_user_id")).isEqualTo(user.getId());
            assertThat(row.get("actor_source")).isEqualTo("AUTH_PASSWORD_RESET");
            assertThat(row.get("ip_address")).isEqualTo(ip);
            assertNoSensitive(row);
        });
        assertThat(authAudits("PASSWORD_RESET_REQUESTED", unknown)).isEmpty();

        PasswordResetToken token = passwordResetTokenRepository.save(PasswordResetToken.builder()
                .token(UUID.randomUUID().toString())
                .email(user.getEmail())
                .expiredAt(OffsetDateTime.now().plusMinutes(10))
                .build());
        MvcResult reset = mockMvc.perform(origin(put("/api/auth/reset-password"), ip).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token.getToken(), "newPassword", "NewPass-998877"))))
                .andReturn();
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        assertThat(authAudits("PASSWORD_RESET_COMPLETED", user.getEmail())).singleElement().satisfies(row -> {
            assertThat(row.get("actor_type")).isEqualTo("USER");
            assertThat(row.get("changed_by")).isEqualTo(user.getId());
            assertThat((String) row.get("new_json")).doesNotContain(token.getToken());
            assertNoSensitive(row);
        });
        passwordResetTokenRepository.findAll().stream()
                .filter(t -> user.getEmail().equals(t.getEmail()))
                .forEach(passwordResetTokenRepository::delete);
    }
}
