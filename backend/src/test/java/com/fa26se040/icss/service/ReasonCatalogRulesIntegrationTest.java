package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.ReasonCatalog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.ReasonCatalogRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 (20/10): danh mục lý do, các BR đang THIẾU TEST trong ma trận truy vết (BR-RC-20..25).
 * BR-RC-21: mã chữ thường bị 400, không tự đổi sang chữ hoa (ghi đúng hành vi hiện tại, không đổi code).
 */
class ReasonCatalogRulesIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/reason-catalogs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReasonCatalogRepository reasonCatalogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<UUID> createdReasonIds = new ArrayList<>();
    private User admin;
    private User fm;
    private User normalUser;

    @BeforeAll
    void setUpUsers() {
        admin = newUser("adm", Role.ADMIN);
        fm = newUser("fm", Role.FACILITY_MANAGER);
        normalUser = newUser("usr", Role.NORMAL_USER);
    }

    @AfterAll
    void cleanUp() {
        // audit_logs chỉ cho thêm (trigger append-only) nên giữ user làm changed_by; chỉ xoá mục lý do test tạo
        reasonCatalogRepository.deleteAllById(createdReasonIds);
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("t1." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("T1-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("T1 " + tag + " " + suffix)
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    private static String uniqueCode() {
        return "T1_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String createBody(String actionType, String code, String label, boolean isOther) {
        return "{\"actionType\":\"" + actionType + "\",\"code\":\"" + code + "\",\"label\":\"" + label
                + "\",\"isOther\":" + isOther + ",\"sortOrder\":5}";
    }

    private MvcResult create(User actor, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        if (result.getResponse().getStatus() == 201) {
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            createdReasonIds.add(UUID.fromString(node.path("data").path("id").asText()));
        }
        return result;
    }

    private UUID createValid(String code) throws Exception {
        MvcResult r = create(admin, createBody("EVENT_ENABLE", code, "Lý do kiểm thử " + code, false));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return createdReasonIds.get(createdReasonIds.size() - 1);
    }

    private int auditCount(UUID id, String action) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'REASON_CATALOG' AND target_id = ? AND action = ?",
                Integer.class, id.toString(), action);
        return n != null ? n : 0;
    }

    private ReasonCatalog seededOther() {
        return reasonCatalogRepository.findAll().stream()
                .filter(r -> "EVENT_ENABLE".equals(r.getActionType()) && Boolean.TRUE.equals(r.getIsOther()))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ BR-RC-20

    @Test
    @DisplayName("BR-RC-20: chỉ ADMIN xem toàn bộ, tạo, sửa, ngừng dùng, kích hoạt lại")
    void rc20_onlyAdminManagesCatalog() throws Exception {
        UUID id = createValid(uniqueCode());
        String createJson = createBody("EVENT_ENABLE", uniqueCode(), "Không được tạo", false);
        for (User u : List.of(fm, normalUser)) {
            mockMvc.perform(get(BASE).header("Authorization", bearer(u))).andExpect(status().isForbidden());
            mockMvc.perform(post(BASE).header("Authorization", bearer(u))
                    .contentType(MediaType.APPLICATION_JSON).content(createJson)).andExpect(status().isForbidden());
            mockMvc.perform(put(BASE + "/{id}", id).header("Authorization", bearer(u))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Đổi nhãn\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch(BASE + "/{id}/deactivate", id).header("Authorization", bearer(u)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch(BASE + "/{id}/reactivate", id).header("Authorization", bearer(u)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(BASE).header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BR-RC-20: FM và ADMIN đọc được lý do đang dùng theo loại thao tác; NORMAL_USER thì không")
    void rc20_activeListForFmAndAdmin() throws Exception {
        mockMvc.perform(get(BASE + "/active").param("actionType", "EVENT_ENABLE").header("Authorization", bearer(fm)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/active").param("actionType", "EVENT_ENABLE").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/active").param("actionType", "EVENT_ENABLE").header("Authorization", bearer(normalUser)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ BR-RC-21

    @Test
    @DisplayName("BR-RC-21: loại thao tác ngoài danh sách -> 400")
    void rc21_invalidActionType() throws Exception {
        MvcResult r = create(admin, createBody("AREA_DELETE", uniqueCode(), "Sai loại", false));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("BR-RC-21: mã có chữ thường -> 400, không tự đổi sang chữ hoa")
    void rc21_lowerCaseCodeRejected() throws Exception {
        String lower = uniqueCode().toLowerCase(Locale.ROOT);
        MvcResult r = create(admin, createBody("EVENT_ENABLE", lower, "Mã chữ thường", false));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(reasonCatalogRepository.existsByActionTypeAndCode("EVENT_ENABLE", lower.toUpperCase(Locale.ROOT)))
                .as("không tạo bản chữ hoa thay thế").isFalse();
    }

    @Test
    @DisplayName("BR-RC-21: mã > 50 ký tự hoặc nhãn > 255 ký tự -> 400; hợp lệ -> 201")
    void rc21_lengthLimits() throws Exception {
        String longCode = "T1_" + "A".repeat(48);
        assertThat(create(admin, createBody("EVENT_ENABLE", longCode, "Mã dài", false)).getResponse().getStatus())
                .isEqualTo(400);
        String longLabel = "N".repeat(256);
        assertThat(create(admin, createBody("EVENT_ENABLE", uniqueCode(), longLabel, false)).getResponse().getStatus())
                .isEqualTo(400);
        String code = uniqueCode();
        MvcResult ok = create(admin, createBody("EVENT_ENABLE", code, "N".repeat(255), false));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("code").asText()).isEqualTo(code);
    }

    // ------------------------------------------------------------------ BR-RC-22

    @Test
    @DisplayName("BR-RC-22: trùng (loại thao tác, mã) -> 400")
    void rc22_duplicateCodeRejected() throws Exception {
        String code = uniqueCode();
        createValid(code);
        assertThat(create(admin, createBody("EVENT_ENABLE", code, "Trùng mã", false)).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("BR-RC-22: mỗi loại thao tác chỉ có một mục 'Khác'")
    void rc22_secondOtherRejected() throws Exception {
        seededOther(); // seed V54_1 có sẵn OTHER cho EVENT_ENABLE
        assertThat(create(admin, createBody("EVENT_ENABLE", uniqueCode(), "Khác thứ hai", true)).getResponse().getStatus())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ BR-RC-23

    @Test
    @DisplayName("BR-RC-23: sửa chỉ đổi nhãn và thứ tự, mã và loại thao tác giữ nguyên")
    void rc23_onlyLabelAndSortOrderChange() throws Exception {
        String code = uniqueCode();
        UUID id = createValid(code);
        mockMvc.perform(put(BASE + "/{id}", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nhãn mới\",\"sortOrder\":42,\"code\":\"HACKED\",\"actionType\":\"EVENT_DISABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.label").value("Nhãn mới"))
                .andExpect(jsonPath("$.data.sortOrder").value(42));
        ReasonCatalog saved = reasonCatalogRepository.findById(id).orElseThrow();
        assertThat(saved.getCode()).isEqualTo(code);
        assertThat(saved.getActionType()).isEqualTo("EVENT_ENABLE");
        assertThat(auditCount(id, "UPDATE")).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-RC-23: sửa mà không đổi gì -> không lưu, không ghi audit")
    void rc23_unchangedEditNotAudited() throws Exception {
        String code = uniqueCode();
        UUID id = createValid(code);
        ReasonCatalog before = reasonCatalogRepository.findById(id).orElseThrow();
        mockMvc.perform(put(BASE + "/{id}", id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + before.getLabel() + "\",\"sortOrder\":" + before.getSortOrder() + "}"))
                .andExpect(status().isOk());
        assertThat(auditCount(id, "UPDATE")).isZero();
    }

    // ------------------------------------------------------------------ BR-RC-24

    @Test
    @DisplayName("BR-RC-24: ngừng dùng mục 'Khác' -> 400 ERR_AREA_029, mục vẫn đang dùng")
    void rc24_otherCannotBeDeactivated() throws Exception {
        ReasonCatalog other = seededOther();
        mockMvc.perform(patch(BASE + "/{id}/deactivate", other.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_AREA_029"));
        assertThat(reasonCatalogRepository.findById(other.getId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("BR-RC-24: ngừng dùng mục đã ngừng -> 200, không ghi audit lần hai")
    void rc24_deactivateInactiveIsNoOp() throws Exception {
        UUID id = createValid(uniqueCode());
        mockMvc.perform(patch(BASE + "/{id}/deactivate", id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
        mockMvc.perform(patch(BASE + "/{id}/deactivate", id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditCount(id, "DEACTIVATE")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ BR-RC-25

    @Test
    @DisplayName("BR-RC-25: tạo, sửa, ngừng dùng, kích hoạt lại đều ghi audit REASON_CATALOG")
    void rc25_everyChangeAudited() throws Exception {
        UUID id = createValid(uniqueCode());
        assertThat(auditCount(id, "CREATE")).isEqualTo(1);

        mockMvc.perform(put(BASE + "/{id}", id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Nhãn audit\"}")).andExpect(status().isOk());
        assertThat(auditCount(id, "UPDATE")).isEqualTo(1);

        mockMvc.perform(patch(BASE + "/{id}/deactivate", id).header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(auditCount(id, "DEACTIVATE")).isEqualTo(1);

        mockMvc.perform(patch(BASE + "/{id}/reactivate", id).header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(auditCount(id, "REACTIVATE")).isEqualTo(1);
    }
}
