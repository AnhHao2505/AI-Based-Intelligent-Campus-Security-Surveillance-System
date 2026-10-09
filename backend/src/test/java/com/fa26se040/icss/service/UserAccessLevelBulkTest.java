package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-AL-28: FM đổi cấp truy cập cho nhiều người (PATCH /api/users/bulk-access-level).
 * Mỗi người đi qua đúng UserService.updateAccessLevel: kết quả từng người UPDATED / UNCHANGED / FAILED,
 * người lỗi không làm hỏng người đúng; tối đa USER_BULK_IMPORT_MAX_ROWS người mỗi lần.
 * Không @Transactional: mỗi người commit riêng, cấu hình ghi cache sau commit.
 */
class UserAccessLevelBulkTest extends AbstractIntegrationTest {

    private static final String URL = "/api/users/bulk-access-level";
    private static final String REASON = "Nâng cấp cho nhóm trợ giảng học kỳ mới";
    private static final String MAX_KEY = ConfigKey.USER_BULK_IMPORT_MAX_ROWS.getKey();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private SystemConfigService systemConfigService;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User fm;
    private User admin;
    private User s1;
    private User s2;
    private User s3;
    private String maxBefore;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        fm = user("fm", Role.FACILITY_MANAGER, 2);
        admin = user("adm", Role.ADMIN, 1);
        s1 = user("s1", Role.NORMAL_USER, 1);
        s2 = user("s2", Role.NORMAL_USER, 1);
        s3 = user("s3", Role.NORMAL_USER, 2);
        maxBefore = systemConfigService.getString(ConfigKey.USER_BULK_IMPORT_MAX_ROWS);
    }

    @AfterEach
    void tearDown() {
        if (!maxBefore.equals(systemConfigService.getString(ConfigKey.USER_BULK_IMPORT_MAX_ROWS))) {
            systemConfigService.update(MAX_KEY, maxBefore, "Trả lại giá trị sau test BR-AL-28", admin.getEmail());
        }
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, int level) {
        User u = userRepository.save(User.builder()
                .userCode("AL28-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AL28 " + tag + " " + suffix)
                .email("al28-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private MvcResult bulk(User actor, List<UUID> ids, Object level, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userIds", ids);
        body.put("accessLevel", level);
        body.put("reason", reason);
        return mockMvc.perform(patch(URL)
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private int level(User u) {
        return jdbc.queryForObject("SELECT access_level FROM users WHERE id = ?", Integer.class, u.getId());
    }

    private int audits(User u) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' AND action = 'UPDATE' AND target_id = ?",
                Integer.class, u.getId().toString());
    }

    private JsonNode item(JsonNode data, User u) {
        for (JsonNode n : data.path("results")) {
            if (u.getId().toString().equals(n.path("userId").asText())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Nhiều người hợp lệ -> UPDATED từng người, cấp mới lưu, mỗi người 1 audit có lý do, cùng correlation_id")
    void validUsers_allUpdated() throws Exception {
        MvcResult r = bulk(fm, List.of(s1.getId(), s2.getId()), 2, REASON);

        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = data(r);
        assertEquals(2, d.path("updated").asInt());
        assertEquals("UPDATED", item(d, s1).path("outcome").asText());
        assertEquals(2, level(s1));
        assertEquals(2, level(s2));
        assertEquals(1, audits(s1));
        assertEquals(1, audits(s2));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(DISTINCT correlation_id) FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' AND target_id IN (?, ?)",
                Integer.class, s1.getId().toString(), s2.getId().toString()));
        assertEquals(REASON, jdbc.queryForObject(
                "SELECT reason FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' AND target_id = ?", String.class, s1.getId().toString()));
    }

    @Test
    @DisplayName("Có người đã ở cấp đích -> UNCHANGED, không audit; người khác vẫn UPDATED")
    void alreadyAtLevel_unchanged() throws Exception {
        JsonNode d = data(bulk(fm, List.of(s1.getId(), s3.getId()), 2, REASON));

        assertEquals(1, d.path("updated").asInt());
        assertEquals(1, d.path("unchanged").asInt());
        assertEquals("UNCHANGED", item(d, s3).path("outcome").asText());
        assertEquals(0, audits(s3));
        assertEquals(2, level(s1));
    }

    @Test
    @DisplayName("Mã không tồn tại + chính FM trong danh sách -> FAILED kèm lý do; người hợp lệ vẫn được đổi")
    void unknownAndSelf_failedOthersOk() throws Exception {
        UUID unknown = UUID.randomUUID();
        JsonNode d = data(bulk(fm, List.of(unknown, fm.getId(), s1.getId()), 3, REASON));

        assertEquals(3, d.path("total").asInt());
        assertEquals(1, d.path("updated").asInt());
        assertEquals(2, d.path("failed").asInt());
        JsonNode unk = null;
        for (JsonNode n : d.path("results")) if (unknown.toString().equals(n.path("userId").asText())) unk = n;
        assertEquals("FAILED", unk.path("outcome").asText());
        assertTrue(unk.path("message").asText().contains("Không tìm thấy"), unk.toString());
        JsonNode self = item(d, fm);
        assertEquals("FAILED", self.path("outcome").asText());
        assertTrue(self.path("message").asText().contains("chính mình"), self.toString());
        assertEquals(2, level(fm));
        assertEquals(0, audits(fm));
        assertEquals(3, level(s1));
    }

    @Test
    @DisplayName("Cấp ngoài 1–3 / lý do < 10 ký tự / danh sách rỗng -> 400 VALIDATION_ERROR, không ai bị đổi")
    void invalidBody_validationError() throws Exception {
        for (MvcResult r : List.of(
                bulk(fm, List.of(s1.getId()), 4, REASON),
                bulk(fm, List.of(s1.getId()), 0, REASON),
                bulk(fm, List.of(s1.getId()), 2, "ngắn"),
                bulk(fm, List.of(), 2, REASON))) {
            assertEquals(400, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertTrue(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("VALIDATION_ERROR"));
        }
        assertEquals(1, level(s1));
    }

    @Test
    @DisplayName("Vượt USER_BULK_IMPORT_MAX_ROWS -> 400 nêu giới hạn, không ai bị đổi")
    void overMax_rejected() throws Exception {
        systemConfigService.update(MAX_KEY, "2", "Hạ giới hạn cho test BR-AL-28", admin.getEmail());

        MvcResult r = bulk(fm, List.of(s1.getId(), s2.getId(), s3.getId()), 3, REASON);

        assertEquals(400, r.getResponse().getStatus());
        String msg = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("message").asText();
        assertTrue(msg.contains("2") && msg.contains("3"), msg);
        assertEquals(1, level(s1));
        assertEquals(1, level(s2));
        assertEquals(2, level(s3));
    }

    @Test
    @DisplayName("ADMIN và NORMAL_USER gọi -> 403, không ai bị đổi")
    void nonFm_forbidden() throws Exception {
        assertEquals(403, bulk(admin, List.of(s1.getId()), 2, REASON).getResponse().getStatus());
        assertEquals(403, bulk(s2, List.of(s1.getId()), 2, REASON).getResponse().getStatus());
        assertEquals(1, level(s1));
    }

    @Test
    @DisplayName("GET /api/users/search?accessLevel= chỉ trả người đúng cấp hiện tại")
    void search_filtersByAccessLevel() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/users/search").param("q", "AL28").param("accessLevel", "1").param("size", "20")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(fm))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        List<String> ids = new ArrayList<>();
        for (JsonNode n : data(r).path("content")) {
            assertEquals(1, n.path("accessLevel").asInt(), n.toString());
            ids.add(n.path("id").asText());
        }
        assertTrue(ids.contains(s1.getId().toString()));
        assertFalse(ids.contains(s3.getId().toString()));
    }
}
