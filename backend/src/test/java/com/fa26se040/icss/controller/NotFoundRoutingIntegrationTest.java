package com.fa26se040.icss.controller;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotFoundRoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String tokenFor(Role role) {
        User u = User.builder()
                .id(UUID.randomUUID())
                .userCode("TEST-" + role.name().substring(0, 3))
                .email(role.name().toLowerCase() + "@fpt.edu.vn")
                .fullName("Test " + role.name())
                .role(role)
                .isActive(true)
                .build();
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    @Test
    @DisplayName("404 cho route không tồn tại: NoResourceFoundException -> 404, code NOT_FOUND, message tiếng Việt")
    void testNonExistentRoute_Returns404() throws Exception {
        mockMvc.perform(get("/api/non-existent-route-for-404-test")
                        .header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy đường dẫn yêu cầu"));
    }
}
