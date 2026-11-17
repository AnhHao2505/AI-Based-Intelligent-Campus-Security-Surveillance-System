package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * V132: ADMIN luôn ở cấp truy cập 3.
 * <ul>
 *   <li>T1: sau Flyway trên DB test, AD-001 ở cấp 3; nếu V132 đã đổi AD-001 thì có đúng 1 audit SYSTEM của V132.</li>
 *   <li>T2: chạy NGUYÊN VĂN câu SQL của V132 (đọc từ classpath) trên dữ liệu dựng trong một transaction, kiểm xong
 *       thì rollback. ADMIN cấp 1 / 2 (kể cả đã xoá mềm) về 3 kèm 1 audit cùng dạng JSON với audit ứng dụng ghi khi FM
 *       đổi cấp; ADMIN cấp 3 và người không phải ADMIN không đổi, không audit; chạy lần hai không đổi dòng nào.</li>
 * </ul>
 */
class V132AdminAccessLevelMigrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "db/migration/V132__admin_access_level_3.sql";
    private static final String SOURCE = "FLYWAY_MIGRATION_V132";
    private static final String REASON = "Quản trị viên luôn ở cấp truy cập 3 (V132)";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void deactivateFixtureUsers() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, int level, OffsetDateTime deletedAt) {
        User u = userRepository.saveAndFlush(User.builder()
                .userCode("V132-" + tag.toUpperCase() + "-" + suffix)
                .fullName("V132 " + tag + " " + suffix)
                .email("v132-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .deletedAt(deletedAt)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private int level(UUID id) {
        return jdbc.queryForObject("SELECT access_level FROM users WHERE id = ?", Integer.class, id);
    }

    private List<Map<String, Object>> levelAudits(UUID userId) {
        return jdbc.queryForList("SELECT actor_type, changed_by, actor_source, reason, correlation_id::text AS correlation, "
                        + "target_id, subject_user_id, area_id, old_value::text AS old_value, new_value::text AS new_value "
                        + "FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' AND action = 'UPDATE' AND target_id = ?",
                userId.toString());
    }

    private int v132AuditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_source = ?", Integer.class, SOURCE);
    }

    private boolean sameJson(String a, String b) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::jsonb = ?::jsonb", Boolean.class, a, b));
    }

    @Test
    @DisplayName("T1 (V132): sau Flyway trên DB test, AD-001 ở cấp 3; audit V132 của AD-001 (nếu có) là 1 dòng SYSTEM, cũ khác 3 -> mới 3")
    void afterFlyway_seedAdminIsLevel3() {
        Map<String, Object> ad = jdbc.queryForMap("SELECT id, role, access_level FROM users WHERE user_code = 'AD-001'");
        assertEquals("ADMIN", ad.get("role"));
        assertEquals(3, ((Number) ad.get("access_level")).intValue(), "AD-001 phải ở cấp 3 sau V132");

        List<Map<String, Object>> audits = jdbc.queryForList("SELECT actor_type, changed_by, "
                + "(old_value->>'accessLevel')::int AS old_level, (new_value->>'accessLevel')::int AS new_level "
                + "FROM audit_logs WHERE actor_source = ? AND target_id = ?", SOURCE, ad.get("id").toString());
        assertTrue(audits.size() <= 1, "V132 ghi tối đa 1 audit cho AD-001: " + audits);
        for (Map<String, Object> a : audits) {
            assertEquals("SYSTEM", a.get("actor_type"));
            assertNull(a.get("changed_by"));
            assertNotEquals(3, ((Number) a.get("old_level")).intValue(), "chỉ ghi khi cấp cũ khác 3");
            assertEquals(3, ((Number) a.get("new_level")).intValue());
        }
    }

    @Test
    @DisplayName("T2 (V132): ADMIN cấp 1 / 2 (kể cả đã xoá mềm) -> 3 kèm 1 audit SYSTEM cùng dạng JSON với ứng dụng; "
            + "ADMIN cấp 3, NORMAL_USER, FM không đổi, không audit; chạy lại không đổi dòng nào")
    void v132_movesAdminsToLevel3_withSystemAudit_andIsIdempotent() throws Exception {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);

        // Phần A (commit): audit do ứng dụng ghi khi FM đổi cấp 1 -> 3, làm mẫu định dạng
        User fm = user("fm", Role.FACILITY_MANAGER, 2, null);
        User reference = user("ref", Role.NORMAL_USER, 1, null);
        MvcResult r = mockMvc.perform(patch("/api/users/{id}/access-level", reference.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(fm))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessLevel\":3,\"reason\":\"Kiểm định dạng audit cho V132\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<Map<String, Object>> appAudits = levelAudits(reference.getId());
        assertEquals(1, appAudits.size(), "tiền đề: ứng dụng đã ghi audit USER_ACCESS_LEVEL");
        Map<String, Object> app = appAudits.get(0);

        // Phần B (rollback): dựng dữ liệu cũ rồi chạy câu SQL của V132 hai lần
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            try {
                User admin1 = user("adm1", Role.ADMIN, 1, null);
                User admin2 = user("adm2", Role.ADMIN, 2, null);
                User adminDeleted = user("admdel", Role.ADMIN, 1, OffsetDateTime.now().minusDays(1));
                User admin3 = user("adm3", Role.ADMIN, 3, null);
                User normal = user("usr", Role.NORMAL_USER, 1, null);
                User fm1 = user("fm1", Role.FACILITY_MANAGER, 1, null);
                Map<String, Object> admin3Before = jdbc.queryForMap("SELECT access_level, updated_at FROM users WHERE id = ?", admin3.getId());

                jdbc.execute(sql);

                String expectedCorrelation = jdbc.queryForObject("SELECT md5('FLYWAY_MIGRATION_V132')::uuid::text", String.class);
                for (User changed : List.of(admin1, admin2, adminDeleted)) {
                    assertEquals(3, level(changed.getId()), changed.getUserCode() + " phải về cấp 3");
                    List<Map<String, Object>> audits = levelAudits(changed.getId());
                    assertEquals(1, audits.size(), "mỗi ADMIN được đổi có 1 audit V132");
                    Map<String, Object> a = audits.get(0);
                    assertEquals("SYSTEM", a.get("actor_type"));
                    assertNull(a.get("changed_by"));
                    assertEquals(SOURCE, a.get("actor_source"));
                    assertEquals(REASON, a.get("reason"));
                    assertEquals(expectedCorrelation, a.get("correlation"));
                    assertEquals(changed.getId().toString(), a.get("target_id"));
                    assertEquals(changed.getId(), a.get("subject_user_id"));
                    assertNull(a.get("area_id"));
                    assertTrue(sameJson("{\"accessLevel\": " + changed.getAccessLevel() + "}", (String) a.get("old_value")),
                            "old_value giữ cấp cũ: " + a.get("old_value"));
                    assertTrue(sameJson("{\"accessLevel\": 3}", (String) a.get("new_value")), String.valueOf(a.get("new_value")));
                }
                // Cùng dạng JSON và cùng cách đặt target / subject / area với audit ứng dụng (cấp 1 -> 3)
                Map<String, Object> migrated = levelAudits(admin1.getId()).get(0);
                assertTrue(sameJson((String) app.get("old_value"), (String) migrated.get("old_value")),
                        "old_value của V132 phải cùng dạng với ứng dụng.\n  app: " + app.get("old_value") + "\n  V132: " + migrated.get("old_value"));
                assertTrue(sameJson((String) app.get("new_value"), (String) migrated.get("new_value")),
                        "new_value của V132 phải cùng dạng với ứng dụng.\n  app: " + app.get("new_value") + "\n  V132: " + migrated.get("new_value"));
                assertEquals(reference.getId().toString(), app.get("target_id"));
                assertEquals(reference.getId(), app.get("subject_user_id"));
                assertNull(app.get("area_id"));

                assertEquals(admin3Before, jdbc.queryForMap("SELECT access_level, updated_at FROM users WHERE id = ?", admin3.getId()),
                        "ADMIN đã ở cấp 3 không đổi");
                assertEquals(1, level(normal.getId()), "NORMAL_USER không đổi");
                assertEquals(1, level(fm1.getId()), "FACILITY_MANAGER không đổi");
                for (User untouched : List.of(admin3, normal, fm1)) {
                    assertEquals(0, levelAudits(untouched.getId()).size(), "không đổi -> không audit");
                }

                // Chạy lại: không còn dòng thoả điều kiện -> không đổi, không audit thêm
                int auditsAfterFirst = v132AuditCount();
                Integer nonLevel3Admins = jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE role = 'ADMIN' AND access_level <> 3", Integer.class);
                assertEquals(0, nonLevel3Admins, "sau V132 không còn ADMIN cấp khác 3");
                jdbc.execute(sql);
                assertEquals(auditsAfterFirst, v132AuditCount(), "chạy lại không ghi audit mới");
                for (User changed : List.of(admin1, admin2, adminDeleted)) {
                    assertEquals(3, level(changed.getId()));
                    assertEquals(1, levelAudits(changed.getId()).size());
                }
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
