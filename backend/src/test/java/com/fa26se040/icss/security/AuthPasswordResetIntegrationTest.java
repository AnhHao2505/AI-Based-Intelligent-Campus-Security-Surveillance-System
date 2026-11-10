package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.AuthService;
import com.fa26se040.icss.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * S2 (20/10): POST /api/auth/send-reset-link trả cùng status và cùng nội dung dù e-mail có tài khoản,
 * tài khoản bị vô hiệu hoá / xoá mềm hay không tồn tại; chỉ tài khoản hợp lệ mới được gửi mail.
 * Body so sánh sau khi bỏ "timestamp" (ApiResponse luôn có thời điểm tạo).
 * S3 (20/10): link trong mail lấy từ app.frontend-base-url (profile test đặt http://frontend.test.local/).
 * PR-AU (P7): PUT /api/auth/reset-password — mọi lỗi token trả cùng status, cùng body "Liên kết không hợp lệ hoặc đã hết hạn."
 * Các lời gọi reset-password đi từ IP riêng để không chạm giới hạn theo IP (AuthIpRateLimiter) của các test khác.
 */
class AuthPasswordResetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationService notificationService;

    // Cùng bộ mock với AuthLoginIntegrationTest để dùng chung Spring context
    @MockBean
    private GoogleTokenVerifier googleTokenVerifier;

    private final List<UUID> createdIds = new ArrayList<>();
    private User active;
    private User deactivated;
    private User softDeleted;

    @BeforeEach
    void setUp() {
        clearInvocations(notificationService);
        active = newUser("act", true, null);
        deactivated = newUser("off", false, null);
        softDeleted = newUser("del", true, OffsetDateTime.now());
    }

    @AfterAll
    void cleanUp() {
        userRepository.findAllById(createdIds).forEach(u ->
                passwordResetTokenRepository.findAll().stream()
                        .filter(t -> u.getEmail().equals(t.getEmail()))
                        .forEach(passwordResetTokenRepository::delete));
        userRepository.deleteAllById(createdIds);
    }

    private User newUser(String tag, boolean isActive, OffsetDateTime deletedAt) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User saved = userRepository.save(User.builder()
                .email("s2." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("S2-" + tag.toUpperCase() + "-" + suffix)
                .fullName("S2 " + tag + " " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(isActive)
                .deletedAt(deletedAt)
                .build());
        createdIds.add(saved.getId());
        return saved;
    }

    private MvcResult sendResetLink(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/send-reset-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    private JsonNode bodyWithoutTimestamp(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        ((ObjectNode) node).remove("timestamp");
        return node;
    }

    @Test
    @DisplayName("S2: e-mail có tài khoản, bị vô hiệu hoá, đã xoá, không tồn tại -> cùng status, cùng body")
    void sameResponseForAllCases() throws Exception {
        MvcResult forActive = sendResetLink(active.getEmail());
        MvcResult forDeactivated = sendResetLink(deactivated.getEmail());
        MvcResult forSoftDeleted = sendResetLink(softDeleted.getEmail());
        MvcResult forUnknown = sendResetLink("s2.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn");

        int status = forActive.getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        assertThat(forDeactivated.getResponse().getStatus()).isEqualTo(status);
        assertThat(forSoftDeleted.getResponse().getStatus()).isEqualTo(status);
        assertThat(forUnknown.getResponse().getStatus()).isEqualTo(status);

        JsonNode expected = bodyWithoutTimestamp(forActive);
        assertThat(expected.path("message").asText()).startsWith("Nếu e-mail tồn tại trong hệ thống");
        assertThat(bodyWithoutTimestamp(forDeactivated)).isEqualTo(expected);
        assertThat(bodyWithoutTimestamp(forSoftDeleted)).isEqualTo(expected);
        assertThat(bodyWithoutTimestamp(forUnknown)).isEqualTo(expected);
    }

    @Test
    @DisplayName("S2: chỉ tài khoản đang hoạt động, chưa xoá mới được gửi mail")
    void mailOnlyForActiveAccount() throws Exception {
        sendResetLink(deactivated.getEmail());
        sendResetLink(softDeleted.getEmail());
        sendResetLink("s2.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn");
        verify(notificationService, never()).sendResetPasswordEmail(anyString(), anyString(), anyInt());

        sendResetLink(active.getEmail());
        verify(notificationService).sendResetPasswordEmail(eq(active.getEmail()), anyString(), anyInt());
    }

    @Test
    @DisplayName("S3: link đặt lại mật khẩu dùng app.frontend-base-url, không còn localhost:5173 viết cứng")
    void resetLinkUsesConfiguredFrontendUrl() throws Exception {
        sendResetLink(active.getEmail());
        verify(notificationService).sendResetPasswordEmail(eq(active.getEmail()), argThat(link ->
                link.matches("http://frontend\\.test\\.local/\\?token=[0-9a-f-]{36}")), anyInt());
    }

    // ------------------------------------------------------------------ PR-AU (P7): đặt lại mật khẩu

    private PasswordResetToken saveToken(String email, boolean used, OffsetDateTime expiredAt) {
        return passwordResetTokenRepository.save(PasswordResetToken.builder()
                .token(UUID.randomUUID().toString())
                .email(email)
                .expiredAt(expiredAt)
                .isUsed(used)
                .build());
    }

    private MvcResult resetPassword(String token, String newPassword, String ip) throws Exception {
        return mockMvc.perform(put("/api/auth/reset-password")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("token", token, "newPassword", newPassword))))
                .andReturn();
    }

    @Test
    @DisplayName("PR-AU: token không hợp lệ / đã dùng / hết hạn / tài khoản vô hiệu hoá -> cùng status, cùng body 'Liên kết không hợp lệ hoặc đã hết hạn.'")
    void resetPassword_allTokenErrors_sameResponse() throws Exception {
        String ip = "10.250.0." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(254));
        OffsetDateTime future = OffsetDateTime.now().plusMinutes(10);
        PasswordResetToken used = saveToken(active.getEmail(), true, future);
        PasswordResetToken expired = saveToken(active.getEmail(), false, OffsetDateTime.now().minusMinutes(1));
        PasswordResetToken inactiveAccount = saveToken(deactivated.getEmail(), false, future);

        MvcResult notFound = resetPassword(UUID.randomUUID().toString(), "NewPass-123456", ip);
        MvcResult forUsed = resetPassword(used.getToken(), "NewPass-123456", ip);
        MvcResult forExpired = resetPassword(expired.getToken(), "NewPass-123456", ip);
        MvcResult forInactive = resetPassword(inactiveAccount.getToken(), "NewPass-123456", ip);

        assertThat(notFound.getResponse().getStatus()).isEqualTo(401);
        JsonNode expected = bodyWithoutTimestamp(notFound);
        assertThat(expected.path("message").asText()).isEqualTo(AuthService.RESET_LINK_INVALID_MESSAGE);
        for (MvcResult other : new MvcResult[]{forUsed, forExpired, forInactive}) {
            assertThat(other.getResponse().getStatus()).isEqualTo(401);
            assertThat(objectMapper.writeValueAsString(bodyWithoutTimestamp(other)))
                    .isEqualTo(objectMapper.writeValueAsString(expected));
        }
        // Không đổi mật khẩu của tài khoản nào
        assertThat(userRepository.findById(deactivated.getId()).orElseThrow().getPassword()).isNull();
    }

    @Test
    @DisplayName("PR-AU: token hợp lệ -> 200, mật khẩu được đổi, token đánh dấu đã dùng (luồng thành công không đổi)")
    void resetPassword_validToken_succeeds() throws Exception {
        PasswordResetToken token = saveToken(active.getEmail(), false, OffsetDateTime.now().plusMinutes(10));
        MvcResult r = resetPassword(token.getToken(), "NewPass-123456", "10.251.0.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(passwordEncoder.matches("NewPass-123456",
                userRepository.findById(active.getId()).orElseThrow().getPassword())).isTrue();
        assertThat(passwordResetTokenRepository.findByToken(token.getToken()).orElseThrow().getIsUsed()).isTrue();
    }
}
