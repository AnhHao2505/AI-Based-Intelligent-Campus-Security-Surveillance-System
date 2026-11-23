package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * B1 (form tạo tài khoản gán lỗi theo ô, không so chữ): trùng mã / email trả 409, errorCode và message như cũ, thêm
 * data = map trường → câu (userCode, email) để FE đặt lỗi dưới đúng ô. Kiểm trùng chạy trước đăng ký khuôn mặt nên không
 * gọi dịch vụ AI / MinIO.
 */
class StaffAccountDuplicateFieldDataIntegrationTest extends AbstractIntegrationTest {

    private static final String CODE_EXISTS = "Mã người dùng đã tồn tại trong hệ thống";
    private static final String EMAIL_EXISTS = "Email đã tồn tại trong hệ thống";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private User existing;

    @BeforeAll
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        admin = userRepository.save(User.builder()
                .userCode("FDDUP-ADM-" + suffix).fullName("Dup admin " + suffix)
                .email("fddup-adm-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.ADMIN).accessLevel(3).isActive(true).build());
        existing = userRepository.save(User.builder()
                .userCode("FDDUP-OLD-" + suffix).fullName("Dup existing " + suffix)
                .email("fddup-old-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.NORMAL_USER).accessLevel(1).isActive(true).build());
    }

    @AfterAll
    void cleanUp() {
        // audit_logs append-only có thể giữ FK tới users -> chỉ khoá, không xoá
        jdbc.update("UPDATE users SET is_active = false WHERE id IN (?, ?)", admin.getId(), existing.getId());
    }

    private JsonNode create(String userCode, String email) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}))
                        .param("fullName", "Duplicate check")
                        .param("userCode", userCode)
                        .param("email", email)
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(409);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.path("errorCode").asText()).isEqualTo("Conflict");
        assertThat(body.path("code").asText()).isEqualTo("Conflict");
        return body;
    }

    private static String freshCode() {
        return "FDDUP-NEW-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("Trùng mã: 409, message cũ, data chỉ có userCode")
    void duplicateCode_dataUserCode() throws Exception {
        JsonNode body = create(existing.getUserCode().toLowerCase(Locale.ROOT), freshCode().toLowerCase(Locale.ROOT) + "@fpt.edu.vn");
        assertThat(body.path("message").asText()).isEqualTo(CODE_EXISTS);
        assertThat(body.path("data").path("userCode").asText()).isEqualTo(CODE_EXISTS);
        assertThat(body.path("data").has("email")).isFalse();
    }

    @Test
    @DisplayName("Trùng email: 409, message cũ, data chỉ có email")
    void duplicateEmail_dataEmail() throws Exception {
        JsonNode body = create(freshCode(), existing.getEmail().toUpperCase(Locale.ROOT));
        assertThat(body.path("message").asText()).isEqualTo(EMAIL_EXISTS);
        assertThat(body.path("data").path("email").asText()).isEqualTo(EMAIL_EXISTS);
        assertThat(body.path("data").has("userCode")).isFalse();
    }

    @Test
    @DisplayName("Trùng cả hai: message nối hai câu như cũ, data có đủ userCode và email")
    void duplicateBoth_dataBothFields() throws Exception {
        JsonNode body = create(existing.getUserCode(), existing.getEmail());
        assertThat(body.path("message").asText()).isEqualTo(CODE_EXISTS + ". " + EMAIL_EXISTS);
        assertThat(body.path("data").path("userCode").asText()).isEqualTo(CODE_EXISTS);
        assertThat(body.path("data").path("email").asText()).isEqualTo(EMAIL_EXISTS);
    }
}
