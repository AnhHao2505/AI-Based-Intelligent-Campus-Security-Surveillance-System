package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q2 (CLAUDE.md mục 9a): GET /api/users/{code} trả email + role nên chỉ dành cho ADMIN.
 * FM và NORMAL_USER tra cứu thành viên qua POST /api/access-requests/resolve-members.
 */
class UserCodeLookupAuthorizationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    private User admin;
    private User fm;
    private User normalUser;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = newUser("adm", Role.ADMIN, 3, suffix);
        fm = newUser("fm", Role.FACILITY_MANAGER, 3, suffix);
        normalUser = newUser("usr", Role.NORMAL_USER, 1, suffix);
    }

    private User newUser(String tag, Role role, int level, String suffix) {
        return userRepository.save(User.builder()
                .email("q2." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("Q2-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Q2 " + tag + " " + suffix)
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    @Test
    @DisplayName("Q2: ADMIN gọi GET /api/users/{code} -> 200")
    void adminCanLookUpByCode() throws Exception {
        mockMvc.perform(get("/api/users/{code}", normalUser.getUserCode()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCode").value(normalUser.getUserCode()));
    }

    @Test
    @DisplayName("Q2: FACILITY_MANAGER gọi GET /api/users/{code} -> 403")
    void facilityManagerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/users/{code}", normalUser.getUserCode()).header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Q2: NORMAL_USER gọi GET /api/users/{code} -> 403")
    void normalUserIsForbidden() throws Exception {
        mockMvc.perform(get("/api/users/{code}", admin.getUserCode()).header("Authorization", bearer(normalUser)))
                .andExpect(status().isForbidden());
    }
}
