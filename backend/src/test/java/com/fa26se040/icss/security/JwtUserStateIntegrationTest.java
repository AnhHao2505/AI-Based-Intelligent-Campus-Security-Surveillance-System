package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1 (20/10): mỗi request có token hợp lệ phải tra user trong DB.
 * Tài khoản vô hiệu hoá / xoá mềm / không còn tồn tại -> 401 dù token chưa hết hạn;
 * role lấy từ DB nên đổi role có hiệu lực ngay với token cũ.
 * Endpoint dùng để kiểm: GET /api/users/{code} (chỉ ADMIN).
 */
class JwtUserStateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    private final List<UUID> createdIds = new ArrayList<>();
    private User target;

    @BeforeEach
    void setUp() {
        target = newUser("tgt", Role.NORMAL_USER);
    }

    @AfterAll
    void cleanUp() {
        userRepository.deleteAllById(createdIds);
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User saved = userRepository.save(User.builder()
                .email("s1." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("S1-" + tag.toUpperCase() + "-" + suffix)
                .fullName("S1 " + tag + " " + suffix)
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
        createdIds.add(saved.getId());
        return saved;
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    @Test
    @DisplayName("S1: ADMIN đang hoạt động gọi API bằng token -> 200")
    void activeUser_tokenAccepted() throws Exception {
        User admin = newUser("adm", Role.ADMIN);
        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S1: vô hiệu hoá tài khoản rồi gọi API bằng token cũ -> 401")
    void deactivatedUser_oldTokenRejected() throws Exception {
        User admin = newUser("adm", Role.ADMIN);
        String token = bearer(admin);
        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isOk());

        admin.setIsActive(false);
        userRepository.save(admin);

        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S1: xoá mềm tài khoản rồi gọi API bằng token cũ -> 401")
    void softDeletedUser_oldTokenRejected() throws Exception {
        User admin = newUser("adm", Role.ADMIN);
        String token = bearer(admin);

        admin.setDeletedAt(OffsetDateTime.now());
        userRepository.save(admin);

        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S1: user không còn trong DB nhưng token còn hạn -> 401")
    void missingUser_tokenRejected() throws Exception {
        User admin = newUser("adm", Role.ADMIN);
        String token = bearer(admin);

        userRepository.deleteById(admin.getId());

        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S1: nâng role NORMAL_USER -> ADMIN, token cũ có quyền ADMIN ngay")
    void roleUpgrade_appliesImmediately() throws Exception {
        User user = newUser("usr", Role.NORMAL_USER);
        String token = bearer(user);
        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isForbidden());

        user.setRole(Role.ADMIN);
        userRepository.save(user);

        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S1: hạ role ADMIN -> NORMAL_USER, token cũ mất quyền ADMIN ngay")
    void roleDowngrade_appliesImmediately() throws Exception {
        User admin = newUser("adm", Role.ADMIN);
        String token = bearer(admin);

        admin.setRole(Role.NORMAL_USER);
        userRepository.save(admin);

        mockMvc.perform(get("/api/users/{code}", target.getUserCode()).header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}
