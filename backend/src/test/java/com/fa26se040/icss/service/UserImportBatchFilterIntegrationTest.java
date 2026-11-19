package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accesscontrol.snapshot.ImportBatchAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lọc theo lô import ở màn Cấp truy cập: GET /api/users/search?importBatchId, GET /api/users/search/import-batches,
 * GET /api/users/search/ids, rồi PATCH /api/users/bulk-access-level.
 * Lô "import" được dựng bằng cách lưu user kèm import_batch_id và ghi audit IMPORT / USER_IMPORT_BATCH như
 * UserBulkImportService làm (nạp ZIP thật cần MinIO, CI không có). Không @Transactional: SystemConfigService chỉ
 * cập nhật cache sau commit. DB test dùng chung -> mọi user fixture bị vô hiệu hoá ở @AfterEach.
 */
class UserImportBatchFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditService auditService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private ObjectMapper objectMapper;

    private final List<User> fixtures = new ArrayList<>();
    private String suffix;
    private User admin;
    private User fm;
    private User normalCaller;
    private User guardCaller;
    private UUID batchA;
    private UUID batchB;
    private User a1;
    private User a2;
    private User a3;
    private User aGuard;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = newUser("ADM", Role.ADMIN, 3, null);
        fm = newUser("FM", Role.FACILITY_MANAGER, 3, null);
        normalCaller = newUser("USR", Role.NORMAL_USER, 1, null);
        guardCaller = newUser("GRD", Role.GUARD, 1, null);

        batchA = UUID.randomUUID();
        batchB = UUID.randomUUID();
        a1 = newUser("A1", Role.NORMAL_USER, 1, batchA);
        a2 = newUser("A2", Role.NORMAL_USER, 1, batchA);
        a3 = newUser("A3", Role.NORMAL_USER, 2, batchA);
        aGuard = newUser("AG", Role.GUARD, 1, batchA);
        newUser("B1", Role.NORMAL_USER, 1, batchB);
        recordImport(batchA, "NORMAL", "lo-a-" + suffix + ".zip", 4);
    }

    @AfterEach
    void tearDown() {
        if (systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS) != 200) {
            systemConfigService.update(ConfigKey.USER_BULK_IMPORT_MAX_ROWS.getKey(), "200", admin.getEmail());
        }
        for (User u : fixtures) {
            userRepository.findById(u.getId()).ifPresent(x -> {
                x.setIsActive(false);
                userRepository.save(x);
            });
        }
        fixtures.clear();
    }

    private User newUser(String tag, Role role, int level, UUID batchId) {
        User u = userRepository.save(User.builder()
                .email("ibf." + tag.toLowerCase() + "." + suffix + "@fpt.edu.vn")
                .userCode("IBF-" + tag + "-" + suffix)
                .fullName("IBF " + tag + " " + suffix)
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .importBatchId(batchId)
                .build());
        fixtures.add(u);
        return u;
    }

    private void recordImport(UUID batchId, String flow, String fileName, int rows) {
        auditService.record(AuditTargetType.USER_IMPORT_BATCH, AuditAction.IMPORT, batchId.toString(), null, null,
                null, ImportBatchAuditSnapshot.imported(flow, fileName, rows, rows, 0), null, AuditActor.user(admin));
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    private Set<String> ids(User... users) {
        Set<String> s = new HashSet<>();
        for (User u : users) s.add(u.getId().toString());
        return s;
    }

    private Set<String> searchIds(Map<String, String> params) throws Exception {
        var req = get("/api/users/search").param("size", "20").header("Authorization", bearer(fm));
        params.forEach(req::param);
        MvcResult r = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        JsonNode content = objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("content");
        Set<String> out = new HashSet<>();
        content.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    private List<String> filterIds(Map<String, String> params, User caller) throws Exception {
        var req = get("/api/users/search/ids").header("Authorization", bearer(caller));
        params.forEach(req::param);
        MvcResult r = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        List<String> out = new ArrayList<>();
        objectMapper.readTree(r.getResponse().getContentAsString()).path("data").forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---------- 1. search lọc theo lô ----------

    @Test
    @DisplayName("Lọc lô: search?importBatchId chỉ trả người hoạt động, chưa xoá của lô; kết hợp q và accessLevel đủ 4 tổ hợp")
    void searchByImportBatch_combinesWithQueryAndLevel() throws Exception {
        User inactive = newUser("AX", Role.NORMAL_USER, 1, batchA);
        inactive.setIsActive(false);
        userRepository.save(inactive);
        User deleted = newUser("AD", Role.NORMAL_USER, 1, batchA);
        deleted.setDeletedAt(OffsetDateTime.now());
        userRepository.save(deleted);

        String batch = batchA.toString();
        assertThat(searchIds(Map.of("importBatchId", batch))).isEqualTo(ids(a1, a2, a3, aGuard));
        assertThat(searchIds(Map.of("importBatchId", batch, "accessLevel", "2"))).isEqualTo(ids(a3));
        assertThat(searchIds(Map.of("importBatchId", batch, "q", "IBF-A2-" + suffix))).isEqualTo(ids(a2));
        assertThat(searchIds(Map.of("importBatchId", batch, "q", suffix, "accessLevel", "1"))).isEqualTo(ids(a1, a2, aGuard));
        assertThat(searchIds(Map.of("importBatchId", batch, "q", "IBF-A3-" + suffix, "accessLevel", "1"))).isEmpty();
    }

    @Test
    @DisplayName("Lọc lô: lô không tồn tại -> danh sách rỗng (200)")
    void searchByUnknownBatch_returnsEmpty() throws Exception {
        assertThat(searchIds(Map.of("importBatchId", UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    @DisplayName("Lọc lô: không truyền importBatchId -> search giữ hành vi cũ (thấy người của nhiều lô)")
    void searchWithoutBatch_keepsOldBehaviour() throws Exception {
        assertThat(searchIds(Map.of("q", "IBF-"))).isNotEmpty();
        assertThat(searchIds(Map.of("q", suffix))).contains(a1.getId().toString(), fm.getId().toString());
    }

    // ---------- 2. quyền ----------

    @Test
    @DisplayName("Danh sách lô: FM đọc được, có tên file + luồng + số người hoạt động, không có tên / email người trong lô")
    void batchOptions_fmCanRead() throws Exception {
        String a = batchA.toString();
        String b = batchB.toString();
        mockMvc.perform(get("/api/users/search/import-batches").header("Authorization", bearer(fm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.importBatchId=='" + a + "')].activeCount").value(hasItem(4)))
                .andExpect(jsonPath("$.data[?(@.importBatchId=='" + a + "')].flow").value(hasItem("NORMAL")))
                .andExpect(jsonPath("$.data[?(@.importBatchId=='" + a + "')].fileName").value(hasItem("lo-a-" + suffix + ".zip")))
                .andExpect(jsonPath("$.data[?(@.importBatchId=='" + b + "')].activeCount").value(hasItem(1)))
                .andExpect(jsonPath("$.data[0].fullName").doesNotExist())
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].userCodes").doesNotExist());
    }

    @Test
    @DisplayName("Danh sách lô: ADMIN 200; NORMAL_USER, GUARD -> 403")
    void batchOptions_otherRoles() throws Exception {
        mockMvc.perform(get("/api/users/search/import-batches").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/search/import-batches").header("Authorization", bearer(normalCaller)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/search/import-batches").header("Authorization", bearer(guardCaller)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Lô import: FM gọi danh sách đầy đủ, chi tiết, xoá, khôi phục lô -> 403 (vẫn chỉ ADMIN)")
    void adminOnlyBatchEndpoints_forbiddenForFm() throws Exception {
        mockMvc.perform(get("/api/users/import-batches").header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/import-batches/{id}", batchA).header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/import-batches/{id}", batchA).header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/import-batches/{id}/restore", batchA).header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findById(a1.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("ids: chỉ FM; ADMIN, NORMAL_USER, GUARD -> 403")
    void filterIds_onlyFm() throws Exception {
        for (User u : List.of(admin, normalCaller, guardCaller)) {
            mockMvc.perform(get("/api/users/search/ids").param("importBatchId", batchA.toString())
                            .header("Authorization", bearer(u)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- 3. ids ----------

    @Test
    @DisplayName("ids: loại ADMIN, FM, chính FM gọi, người không hoạt động, đã xoá mềm; giữ NORMAL_USER và GUARD")
    void filterIds_excludesNonAssignable() throws Exception {
        newUser("AA", Role.ADMIN, 3, batchA);
        newUser("AF", Role.FACILITY_MANAGER, 3, batchA);
        fm.setImportBatchId(batchA);
        userRepository.save(fm);
        User inactive = newUser("AX", Role.NORMAL_USER, 1, batchA);
        inactive.setIsActive(false);
        userRepository.save(inactive);
        User deleted = newUser("AD", Role.NORMAL_USER, 1, batchA);
        deleted.setDeletedAt(OffsetDateTime.now());
        userRepository.save(deleted);

        List<String> got = filterIds(Map.of("importBatchId", batchA.toString()), fm);
        assertThat(new HashSet<>(got)).isEqualTo(ids(a1, a2, a3, aGuard));
        assertThat(got).hasSize(4);

        assertThat(new HashSet<>(filterIds(Map.of("importBatchId", batchA.toString(), "accessLevel", "2"), fm)))
                .isEqualTo(ids(a3));
    }

    @Test
    @DisplayName("ids: không lọc lô (chỉ q) vẫn chạy, cũng loại ADMIN / FM / chính mình")
    void filterIds_withoutBatch() throws Exception {
        List<String> got = filterIds(Map.of("q", suffix), fm);
        assertThat(got).contains(a1.getId().toString(), aGuard.getId().toString(), normalCaller.getId().toString(),
                guardCaller.getId().toString());
        assertThat(got).doesNotContain(admin.getId().toString(), fm.getId().toString());
    }

    @Test
    @DisplayName("ids: số kết quả bằng giới hạn -> 200; vượt 1 -> 400 nêu N và M, không cắt bớt")
    void filterIds_limit() throws Exception {
        systemConfigService.update(ConfigKey.USER_BULK_IMPORT_MAX_ROWS.getKey(), "4", admin.getEmail());

        assertThat(filterIds(Map.of("importBatchId", batchA.toString()), fm)).hasSize(4);

        newUser("A5", Role.NORMAL_USER, 1, batchA);
        mockMvc.perform(get("/api/users/search/ids").param("importBatchId", batchA.toString())
                        .header("Authorization", bearer(fm)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kết quả lọc có 5 người, vượt giới hạn 4 mỗi lần; hãy lọc hẹp hơn"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------- 4. luồng đầy đủ ----------

    @Test
    @DisplayName("Luồng: lô import -> FM chọn lô -> ids -> bulk-access-level -> mọi người trong lô lên cấp 2, có lý do, có audit")
    void fullFlow_batchToBulkAccessLevel() throws Exception {
        String reason = "Nâng cấp cho lô cán bộ mới nhập " + suffix;

        assertThat(searchIds(Map.of("importBatchId", batchA.toString()))).hasSize(4);
        List<String> idList = filterIds(Map.of("importBatchId", batchA.toString()), fm);
        assertThat(idList).hasSize(4);

        String body = objectMapper.writeValueAsString(Map.of("userIds", idList, "accessLevel", 2, "reason", reason));
        mockMvc.perform(patch("/api/users/bulk-access-level").header("Authorization", bearer(fm))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.updated").value(3))
                .andExpect(jsonPath("$.data.unchanged").value(1))
                .andExpect(jsonPath("$.data.failed").value(0));

        for (User u : List.of(a1, a2, a3, aGuard)) {
            assertThat(userRepository.findById(u.getId()).orElseThrow().getAccessLevel()).isEqualTo(2);
        }

        List<String> changed = List.of(a1.getId().toString(), a2.getId().toString(), aGuard.getId().toString());
        Specification<AuditLog> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("targetType"), AuditTargetType.USER_ACCESS_LEVEL),
                cb.equal(root.get("action"), AuditAction.UPDATE),
                root.get("targetId").in(changed));
        List<AuditLog> audits = auditLogRepository.findAll(spec);
        assertThat(audits).hasSize(3);
        assertThat(audits).allSatisfy(a -> {
            assertThat(a.getReason()).isEqualTo(reason);
            assertThat(a.getNewValue()).contains("2");
        });
        // a3 đã ở cấp 2 -> không đổi, không ghi audit (BR-AL-06)
        Specification<AuditLog> unchangedSpec = (root, q, cb) -> cb.and(
                cb.equal(root.get("targetType"), AuditTargetType.USER_ACCESS_LEVEL),
                cb.equal(root.get("targetId"), a3.getId().toString()));
        assertThat(auditLogRepository.findAll(unchangedSpec)).isEmpty();
    }
}
