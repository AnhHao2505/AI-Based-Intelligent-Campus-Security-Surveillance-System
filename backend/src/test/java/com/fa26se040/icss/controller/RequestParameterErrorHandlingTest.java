package com.fa26se040.icss.controller;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * B-06: thiếu query param bắt buộc / sai kiểu tham số phải trả 400 theo format lỗi chung
 * ({ httpCode, message, errorCode, code, status }), không rơi vào handler chung 500 "Lỗi hệ thống".
 */
class RequestParameterErrorHandlingTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private User fm;
    private User admin;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        fm = user("fm", Role.FACILITY_MANAGER, suffix);
        admin = user("adm", Role.ADMIN, suffix);
    }

    @AfterEach
    void tearDown() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, String suffix) {
        User u = userRepository.save(User.builder()
                .userCode("B06-" + tag.toUpperCase() + "-" + suffix)
                .fullName("B06 " + tag + " " + suffix)
                .email("b06-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private MvcResult call(String url, User actor) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("B-06: GET /api/reason-catalogs/active thiếu actionType -> 400 nêu tên tham số (trước đây 500)")
    void missingRequiredParam_returns400() throws Exception {
        MvcResult r = call("/api/reason-catalogs/active", fm);

        assertEquals(400, r.getResponse().getStatus());
        JsonNode body = json(r);
        assertEquals(400, body.path("httpCode").asInt());
        assertEquals(400, body.path("status").asInt());
        assertEquals("Bad Request", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("actionType"), body.toString());
        assertFalse(body.path("message").asText().contains("Lỗi hệ thống"), body.toString());
    }

    @Test
    @DisplayName("B-06: kèm actionType thì vẫn 200 với FM (không ảnh hưởng luồng đúng)")
    void withRequiredParam_ok() throws Exception {
        assertEquals(200, call("/api/reason-catalogs/active?actionType=EVENT_ENABLE", fm).getResponse().getStatus());
    }

    @Test
    @DisplayName("B-06: sai kiểu tham số (active=abc) -> 400 'Giá trị … không hợp lệ cho tham số active'")
    void typeMismatchParam_returns400() throws Exception {
        MvcResult r = call("/api/reason-catalogs?active=abc", admin);

        assertEquals(400, r.getResponse().getStatus());
        assertTrue(json(r).path("message").asText().contains("active"), json(r).toString());
    }
}
