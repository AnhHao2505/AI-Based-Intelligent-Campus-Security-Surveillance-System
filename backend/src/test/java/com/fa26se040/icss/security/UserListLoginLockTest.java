package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * LT-03: danh sách tài khoản của ADMIN trả {@code loginLockedUntil} khi tài khoản đang bị khoá đăng nhập tạm (V114),
 * để màn quản lý tài khoản hiện "Đang khoá đăng nhập đến HH:mm". Hết khoá / không khoá -> null. FM (chỉ xem bảo vệ) -> null.
 */
class UserListLoginLockTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private User user(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .userCode("LT03-" + tag.toUpperCase() + "-" + suffix)
                .email("lt03." + tag + "." + suffix + "@fpt.edu.vn")
                .fullName("LT03 " + tag + " " + suffix)
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private JsonNode listItem(User caller, User target) throws Exception {
        String body = mockMvc.perform(get("/api/users").param("keyword", target.getUserCode())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(caller)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode content = objectMapper.readTree(body).path("data").path("users").path("content");
        assertThat(content.size()).as(body).isEqualTo(1);
        return content.get(0);
    }

    private void setLockedUntil(User u, OffsetDateTime until) {
        jdbc.update("UPDATE users SET login_locked_until = ?, failed_login_count = 5 WHERE id = ?", until, u.getId());
    }

    @Test
    @DisplayName("ADMIN: đang khoá -> loginLockedUntil = thời điểm hết khoá; khoá đã hết hạn / không khoá -> null")
    void admin_seesLockExpiryOnlyWhileLocked() throws Exception {
        User admin = user("adm", Role.ADMIN);
        User locked = user("lck", Role.NORMAL_USER);
        User expired = user("exp", Role.NORMAL_USER);
        User normal = user("nrm", Role.NORMAL_USER);
        OffsetDateTime until = OffsetDateTime.now().plusMinutes(12).withNano(0);
        setLockedUntil(locked, until);
        setLockedUntil(expired, OffsetDateTime.now().minusMinutes(1));

        JsonNode lockedItem = listItem(admin, locked);
        assertThat(lockedItem.path("isActive").asBoolean()).isTrue();
        assertThat(OffsetDateTime.parse(lockedItem.path("loginLockedUntil").asText()).toInstant()).isEqualTo(until.toInstant());
        assertThat(listItem(admin, expired).path("loginLockedUntil").isNull()).isTrue();
        assertThat(listItem(admin, normal).path("loginLockedUntil").isNull()).isTrue();
    }

    @Test
    @DisplayName("FM (danh sách bảo vệ): bảo vệ đang khoá -> không có loginLockedUntil")
    void fm_doesNotSeeLockExpiry() throws Exception {
        User fm = user("fm", Role.FACILITY_MANAGER);
        User guard = user("grd", Role.GUARD);
        setLockedUntil(guard, OffsetDateTime.now().plusMinutes(10));

        assertThat(listItem(fm, guard).path("loginLockedUntil").isNull()).isTrue();
    }
}
