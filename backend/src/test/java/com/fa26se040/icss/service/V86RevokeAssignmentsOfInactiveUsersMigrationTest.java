package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * T8: chạy NGUYÊN VĂN câu SQL của V86 (đọc từ file migration trên classpath) trên dữ liệu dựng trong một transaction,
 * kiểm xong thì rollback (không để lại thay đổi trên DB test).
 * Định dạng JSON audit được đối chiếu với audit do ứng dụng ghi (vô hiệu hoá qua HTTP, revokeAllOfUser) cho AP có
 * cùng validFrom / validTo: old_value và new_value phải bằng nhau (so jsonb).
 */
class V86RevokeAssignmentsOfInactiveUsersMigrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "db/migration/V86__revoke_assignments_of_inactive_users.sql";
    private static final String REASON_DEACTIVATED = "Tài khoản người được gán đã bị vô hiệu hoá";
    private static final String REASON_DELETED = "Tài khoản người được gán đã bị xoá";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;

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

    private User user(String tag, Role role, boolean active, OffsetDateTime deletedAt) {
        User u = userRepository.saveAndFlush(User.builder()
                .userCode("V86-" + tag.toUpperCase() + "-" + suffix)
                .fullName("V86 " + tag + " " + suffix)
                .email("v86-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(role == Role.NORMAL_USER ? 1 : 3)
                .isActive(active)
                .deletedAt(deletedAt)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private Area area(String tag) {
        Building building = buildingRepository.findByNameIgnoreCase("Tòa V86")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa V86").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa V86", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        return areaRepository.saveAndFlush(Area.builder()
                .name("V86 " + tag + " " + suffix)
                .building("Tòa V86")
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private AreaAssignedPersonnel ap(Area area, User user, User createdBy, OffsetDateTime validFrom, OffsetDateTime validTo,
                                     boolean revoked) {
        OffsetDateTime now = OffsetDateTime.now();
        return assignedPersonnelRepository.saveAndFlush(AreaAssignedPersonnel.builder()
                .area(area)
                .user(user)
                .validFrom(validFrom)
                .validTo(validTo)
                .createdBy(createdBy)
                .createdAt(now)
                .updatedAt(now)
                .revokedAt(revoked ? now.minusHours(1) : null)
                .revokedBy(revoked ? createdBy : null)
                .revokeReason(revoked ? "Thu hồi trước để thử V86" : null)
                .build());
    }

    private Map<String, Object> apRow(UUID apId) {
        return jdbc.queryForMap("SELECT revoked_at, revoked_by, revoke_reason FROM area_assigned_personnel WHERE id = ?", apId);
    }

    private List<Map<String, Object>> revokeAudits(UUID apId) {
        return jdbc.queryForList("SELECT actor_type, changed_by, actor_source, reason, correlation_id::text AS correlation, "
                        + "subject_user_id, area_id, old_value::text AS old_value, new_value::text AS new_value "
                        + "FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?",
                apId.toString());
    }

    private void assertSameJson(UUID appApId, UUID migratedApId, String column) {
        String app = (String) revokeAudits(appApId).get(0).get(column);
        String migrated = (String) revokeAudits(migratedApId).get(0).get(column);
        Boolean equal = jdbc.queryForObject("SELECT ?::jsonb = ?::jsonb", Boolean.class, app, migrated);
        assertEquals(Boolean.TRUE, equal, column + " của V86 phải cùng dạng với audit ứng dụng.\n  app: " + app + "\n  V86: " + migrated);
    }

    @Test
    @DisplayName("T8 (V86): AP của user inactive / deleted bị thu hồi đúng lý do, revoked_by NULL, audit SYSTEM FLYWAY_MIGRATION_V86 "
            + "cùng dạng JSON với ứng dụng; user active, AP hết hạn / đã thu hồi không đổi")
    void v86_revokesAssignmentsOfInactiveAndDeletedUsers() throws Exception {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        // Có phần lẻ giây (bỏ số 0 cuối) và không có phần lẻ, validTo có / không
        OffsetDateTime fromActive = base.minusDays(1).withNano(120_000_000);
        OffsetDateTime toActive = base.plusDays(5).withNano(456_789_000);
        OffsetDateTime fromUpcoming = base.plusDays(2);

        User admin = user("adm", Role.ADMIN, true, null);
        User fm = user("fm", Role.FACILITY_MANAGER, true, null);
        Area area1 = area("a1");
        Area area2 = area("a2");

        // Phần A (commit): audit do ứng dụng ghi, làm mẫu định dạng
        User reference = user("ref", Role.NORMAL_USER, true, null);
        UUID refActive = ap(area1, reference, fm, fromActive, toActive, false).getId();
        UUID refUpcoming = ap(area2, reference, fm, fromUpcoming, null, false).getId();
        MvcResult r = mockMvc.perform(patch("/api/users/{id}/toggle-active", reference.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin))).andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, revokeAudits(refActive).size(), "tiền đề: ứng dụng đã ghi audit REVOKE");
        assertEquals(1, revokeAudits(refUpcoming).size());
        // Định dạng ứng dụng ghi (đã đọc từ dòng audit thật): ISO-8601 giờ Việt Nam, bỏ số 0 cuối phần lẻ giây, validTo null
        Map<String, Object> refJson = jdbc.queryForMap("SELECT old_value->>'validFrom' AS vf, old_value->>'validTo' AS vt "
                + "FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' "
                + "AND target_id = ?", refActive.toString());
        java.time.ZoneId vn = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        assertEquals(fromActive.atZoneSameInstant(vn).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), refJson.get("vf"));
        assertTrue(((String) refJson.get("vf")).endsWith(".12+07:00"), String.valueOf(refJson.get("vf")));
        assertEquals(toActive.atZoneSameInstant(vn).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), refJson.get("vt"));
        Map<String, Object> refUpJson = jdbc.queryForMap("SELECT old_value->>'validTo' AS vt, jsonb_exists(old_value, 'validTo') AS has_vt "
                + "FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?", refUpcoming.toString());
        assertEquals(Boolean.TRUE, refUpJson.get("has_vt"), "validTo null vẫn có khoá (ghi null)");
        assertNull(refUpJson.get("vt"));

        // Phần B (rollback): dựng dữ liệu cũ rồi chạy câu SQL của V86
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            try {
                User inactive = user("ina", Role.NORMAL_USER, false, null);
                User deleted = user("del", Role.NORMAL_USER, false, OffsetDateTime.now().minusDays(1));
                User active = user("act", Role.NORMAL_USER, true, null);
                UUID inaActive = ap(area1, inactive, fm, fromActive, toActive, false).getId();
                UUID inaUpcoming = ap(area2, inactive, fm, fromUpcoming, null, false).getId();
                UUID inaExpired = ap(area2, inactive, fm, base.minusDays(5), base.minusDays(4), false).getId();
                UUID inaRevoked = ap(area1, inactive, fm, base.minusDays(9), base.minusDays(6), true).getId();
                UUID delActive = ap(area1, deleted, fm, fromActive, null, false).getId();
                UUID actActive = ap(area1, active, fm, fromActive, null, false).getId();
                Map<String, Object> expiredBefore = apRow(inaExpired);
                Map<String, Object> revokedBefore = apRow(inaRevoked);

                jdbc.execute(sql);

                for (UUID id : List.of(inaActive, inaUpcoming)) {
                    Map<String, Object> row = apRow(id);
                    assertNotNull(row.get("revoked_at"));
                    assertNull(row.get("revoked_by"), "V86: revoked_by NULL");
                    assertEquals(REASON_DEACTIVATED, row.get("revoke_reason"));
                }
                Map<String, Object> delRow = apRow(delActive);
                assertNotNull(delRow.get("revoked_at"));
                assertEquals(REASON_DELETED, delRow.get("revoke_reason"), "user đã xoá -> lý do \"đã bị xoá\"");
                assertNull(apRow(actActive).get("revoked_at"), "user active không đổi");
                assertEquals(expiredBefore, apRow(inaExpired), "AP đã hết hạn không đổi");
                assertEquals(revokedBefore, apRow(inaRevoked), "AP đã thu hồi không đổi");

                String expectedCorrelation = jdbc.queryForObject("SELECT md5('FLYWAY_MIGRATION_V86')::uuid::text", String.class);
                for (UUID id : List.of(inaActive, inaUpcoming, delActive)) {
                    List<Map<String, Object>> audits = revokeAudits(id);
                    assertEquals(1, audits.size(), "mỗi AP 1 audit V86");
                    Map<String, Object> a = audits.get(0);
                    assertEquals("SYSTEM", a.get("actor_type"));
                    assertNull(a.get("changed_by"));
                    assertEquals("FLYWAY_MIGRATION_V86", a.get("actor_source"));
                    assertEquals(expectedCorrelation, a.get("correlation"));
                    assertEquals(apRow(id).get("revoke_reason"), a.get("reason"));
                    assertNotNull(a.get("subject_user_id"));
                    assertEquals(area(id), a.get("area_id"));
                }
                for (UUID id : List.of(actActive, inaExpired, inaRevoked)) {
                    assertEquals(0, revokeAudits(id).size(), "không thu hồi -> không audit");
                }

                // Cùng dạng JSON với ứng dụng: ACTIVE có validTo (phần lẻ giây) và UPCOMING validTo null
                assertSameJson(refActive, inaActive, "old_value");
                assertSameJson(refActive, inaActive, "new_value");
                assertSameJson(refUpcoming, inaUpcoming, "old_value");
                assertSameJson(refUpcoming, inaUpcoming, "new_value");
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private UUID area(UUID apId) {
        return jdbc.queryForObject("SELECT area_id FROM area_assigned_personnel WHERE id = ?", UUID.class, apId);
    }
}
