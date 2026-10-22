package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-AP-24..27: vô hiệu hoá / xoá tài khoản (lẻ hoặc theo lô import) -> thu hồi mọi AP ACTIVE + UPCOMING của người đó,
 * revoked_by = ADMIN đang thao tác, audit AREA_ASSIGNMENT / REVOKE cùng correlation, không gửi thông báo.
 * Kích hoạt lại / khôi phục không trả lại AP. Đi qua HTTP thật (AuditCorrelationFilter đặt correlation như production).
 * Không @Transactional: fixture được commit như RequestCancelOnRequesterDeactivationTest.
 */
class AssignmentRevokeOnUserDeactivationTest extends AbstractIntegrationTest {

    private static final String REASON_DEACTIVATED = "Tài khoản người được gán đã bị vô hiệu hoá";
    private static final String REASON_DELETED = "Tài khoản người được gán đã bị xoá";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private AccessDecisionService accessDecisionService;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private Floor floor;
    private User admin;
    private User fm;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa APR")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa APR").build()));
        floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa APR", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        admin = user("adm", Role.ADMIN, 3, null, true);
        fm = user("fm", Role.FACILITY_MANAGER, 3, null, true);
    }

    @AfterEach
    void deactivateFixtureUsers() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, int level, UUID importBatchId, boolean active) {
        User u = userRepository.save(User.builder()
                .userCode("APR-" + tag.toUpperCase() + "-" + suffix)
                .fullName("APR " + tag + " " + suffix)
                .email("apr-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(active)
                .importBatchId(importBatchId)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private Area area(String tag, AreaLevel level) {
        return areaRepository.save(Area.builder()
                .name("APR " + tag + " " + suffix)
                .building("Tòa APR")
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(level)
                .areaAccessLevel(level.level())
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private AreaAssignedPersonnel ap(Area area, User user, OffsetDateTime validFrom, OffsetDateTime validTo, boolean revoked) {
        OffsetDateTime now = now();
        return assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(user)
                .validFrom(validFrom)
                .validTo(validTo)
                .createdBy(fm)
                .createdAt(now)
                .updatedAt(now)
                .revokedAt(revoked ? now.minusMinutes(30) : null)
                .revokedBy(revoked ? fm : null)
                .revokeReason(revoked ? "Thu hồi trước để thử APR" : null)
                .build());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private MvcResult send(MockHttpServletRequestBuilder b, User actor) throws Exception {
        return mockMvc.perform(b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))).andReturn();
    }

    private String describe(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void toggle(User u, boolean expectedActive) throws Exception {
        MvcResult r = send(patch("/api/users/{id}/toggle-active", u.getId()), admin);
        assertEquals(200, r.getResponse().getStatus(), describe(r));
        assertEquals(expectedActive, jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, u.getId()));
    }

    private Map<String, Object> apRow(AreaAssignedPersonnel ap) {
        return jdbc.queryForMap("SELECT revoked_at, revoked_by, revoke_reason FROM area_assigned_personnel WHERE id = ?", ap.getId());
    }

    private List<Map<String, Object>> apRevokeAudits(AreaAssignedPersonnel ap) {
        return jdbc.queryForList("SELECT actor_type, changed_by, actor_source, reason, correlation_id, subject_user_id, area_id, "
                        + "old_value->>'status' AS old_status, new_value->>'status' AS new_status "
                        + "FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?",
                ap.getId().toString());
    }

    private int auditCountOf(AreaAssignedPersonnel ap) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND target_id = ?",
                Integer.class, ap.getId().toString());
    }

    private int notificationsOf(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?", Integer.class, u.getId());
    }

    private void assertRevoked(AreaAssignedPersonnel ap, String reason) {
        Map<String, Object> row = apRow(ap);
        assertNotNull(row.get("revoked_at"), "AP phải bị thu hồi");
        assertEquals(admin.getId(), row.get("revoked_by"), "revoked_by = ADMIN đang thao tác");
        assertEquals(reason, row.get("revoke_reason"));
        List<Map<String, Object>> audits = apRevokeAudits(ap);
        assertEquals(1, audits.size(), "mỗi AP 1 audit REVOKE");
        Map<String, Object> a = audits.get(0);
        assertEquals("USER", a.get("actor_type"));
        assertEquals(admin.getId(), a.get("changed_by"));
        assertNull(a.get("actor_source"));
        assertEquals(reason, a.get("reason"));
        assertEquals(ap.getUser().getId(), a.get("subject_user_id"));
        assertEquals(ap.getArea().getId(), a.get("area_id"));
        assertEquals("REVOKED", a.get("new_status"));
    }

    // ================================================================== T1

    @Test
    @DisplayName("T1 (BR-AP-24, 26): vô hiệu hoá -> AP ACTIVE + UPCOMING bị thu hồi (revoked_by ADMIN, đúng lý do, audit cùng correlation), EXPIRED / REVOKED giữ nguyên, không thông báo, đơn vẫn bị huỷ")
    void deactivate_revokesActiveAndUpcoming_only() throws Exception {
        OffsetDateTime now = now();
        User target = user("t1", Role.NORMAL_USER, 1, null, true);
        AreaAssignedPersonnel active = ap(area("t1-active", AreaLevel.HIGHLY_CONFIDENTIAL), target, now.minusDays(1), null, false);
        AreaAssignedPersonnel upcoming = ap(area("t1-upcoming", AreaLevel.CONFIDENTIAL), target, now.plusDays(2), now.plusDays(5), false);
        AreaAssignedPersonnel expired = ap(area("t1-expired", AreaLevel.CONFIDENTIAL), target, now.minusDays(5), now.minusDays(1), false);
        AreaAssignedPersonnel revoked = ap(area("t1-revoked", AreaLevel.HIGHLY_CONFIDENTIAL), target, now.minusDays(3), null, true);
        Map<String, Object> revokedBefore = apRow(revoked);
        AccessRequest pending = accessRequestRepository.save(AccessRequest.builder()
                .area(upcoming.getArea())
                .requester(target)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn cá nhân APR " + suffix)
                .startTime(now.plusDays(1))
                .endTime(now.plusDays(1).plusHours(2))
                .status(RequestStatus.PENDING)
                .build());

        toggle(target, false);

        assertRevoked(active, REASON_DEACTIVATED);
        assertRevoked(upcoming, REASON_DEACTIVATED);
        assertEquals("ACTIVE", apRevokeAudits(active).get(0).get("old_status"));
        assertEquals("UPCOMING", apRevokeAudits(upcoming).get(0).get("old_status"));

        assertNull(apRow(expired).get("revoked_at"), "AP đã hết hạn không đổi");
        assertEquals(revokedBefore, apRow(revoked), "AP đã thu hồi trước đó không đổi");
        assertEquals(0, auditCountOf(expired));
        assertEquals(0, auditCountOf(revoked));

        int userAudits = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' "
                + "AND subject_user_id = ?", Integer.class, target.getId());
        assertEquals(2, userAudits, "đúng 2 dòng audit AREA_ASSIGNMENT / REVOKE");
        Object correlation = apRevokeAudits(active).get(0).get("correlation_id");
        assertNotNull(correlation);
        assertEquals(correlation, apRevokeAudits(upcoming).get(0).get("correlation_id"), "2 audit AP cùng correlation");

        assertEquals(0, notificationsOf(target), "BR-AP-26: không gửi thông báo cho người bị thu hồi");

        Map<String, Object> req = jdbc.queryForMap("SELECT status, cancel_source FROM access_requests WHERE id = ?", pending.getId());
        assertEquals("CANCELLED", req.get("status"), "đơn của người bị vô hiệu hoá vẫn bị huỷ như cũ");
        assertEquals("SYSTEM", req.get("cancel_source"));
        Object cancelCorrelation = jdbc.queryForObject("SELECT correlation_id FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' "
                + "AND action = 'CANCEL' AND target_id = ?", Object.class, pending.getId().toString());
        assertEquals(correlation, cancelCorrelation, "audit huỷ đơn và audit thu hồi AP cùng một thao tác -> cùng correlation");
    }

    // ================================================================== T2

    @Test
    @DisplayName("T2 (BR-AP-24): kích hoạt lại -> AP vẫn REVOKED, checkEntry vào khu Tuyệt mật từng có AP trả DENIED (user cấp 1)")
    void reactivate_doesNotRestoreAssignment() throws Exception {
        OffsetDateTime now = now();
        User target = user("t2", Role.NORMAL_USER, 1, null, true);
        Area highly = area("t2-highly", AreaLevel.HIGHLY_CONFIDENTIAL);
        AreaAssignedPersonnel active = ap(highly, target, now.minusDays(1), null, false);

        AccessDecision before = accessDecisionService.checkEntry(target.getId(), highly.getId(), now.plusMinutes(1));
        assertTrue(before.allowed(), "Tiền đề: có AP thì vào được khu Tuyệt mật — " + before.reason());

        toggle(target, false);
        toggle(target, true);

        assertRevoked(active, REASON_DEACTIVATED);
        AccessDecision after = accessDecisionService.checkEntry(target.getId(), highly.getId(), now.plusMinutes(1));
        assertFalse(after.allowed(), "Kích hoạt lại không trả AP, user cấp 1 không vào được khu Tuyệt mật — " + after.reason());
    }

    // ================================================================== T3

    @Test
    @DisplayName("T3 (BR-AP-25): xoá mềm user có AP ACTIVE -> AP bị thu hồi, lý do \"đã bị xoá\"")
    void softDelete_revokesWithDeletedReason() throws Exception {
        OffsetDateTime now = now();
        User target = user("t3", Role.NORMAL_USER, 1, null, true);
        AreaAssignedPersonnel active = ap(area("t3-active", AreaLevel.CONFIDENTIAL), target, now.minusHours(2), now.plusDays(3), false);

        MvcResult r = send(delete("/api/users/{id}", target.getId()), admin);
        assertEquals(200, r.getResponse().getStatus(), describe(r));

        assertRevoked(active, REASON_DELETED);
        assertEquals(0, notificationsOf(target));
    }

    // ================================================================== T4

    @Test
    @DisplayName("T4 (BR-AP-25): xoá lô import 2 user -> thu hồi đủ AP của cả 2, cùng correlation; khôi phục lô -> AP vẫn REVOKED")
    void deleteBatch_revokesAll_restoreDoesNotGiveBack() throws Exception {
        OffsetDateTime now = now();
        UUID batchId = UUID.randomUUID();
        User u1 = user("t4a", Role.NORMAL_USER, 1, batchId, true);
        User u2 = user("t4b", Role.NORMAL_USER, 2, batchId, true);
        Area a1 = area("t4-1", AreaLevel.HIGHLY_CONFIDENTIAL);
        Area a2 = area("t4-2", AreaLevel.CONFIDENTIAL);
        AreaAssignedPersonnel ap1 = ap(a1, u1, now.minusDays(1), null, false);
        AreaAssignedPersonnel ap2 = ap(a2, u1, now.plusDays(1), null, false);
        AreaAssignedPersonnel ap3 = ap(a1, u2, now.minusHours(1), now.plusDays(10), false);

        MvcResult r = send(delete("/api/users/import-batches/{batchId}", batchId), admin);
        assertEquals(200, r.getResponse().getStatus(), describe(r));

        for (AreaAssignedPersonnel ap : List.of(ap1, ap2, ap3)) {
            assertRevoked(ap, REASON_DELETED);
        }
        Object correlation = apRevokeAudits(ap1).get(0).get("correlation_id");
        assertEquals(correlation, apRevokeAudits(ap2).get(0).get("correlation_id"));
        assertEquals(correlation, apRevokeAudits(ap3).get(0).get("correlation_id"), "cả lô là một thao tác -> một correlation");

        MvcResult restore = send(post("/api/users/import-batches/{batchId}/restore", batchId), admin);
        assertEquals(200, restore.getResponse().getStatus(), describe(restore));
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, u1.getId()), "tiền đề: lô đã khôi phục");

        for (AreaAssignedPersonnel ap : List.of(ap1, ap2, ap3)) {
            Map<String, Object> row = apRow(ap);
            assertNotNull(row.get("revoked_at"), "khôi phục lô không trả lại AP");
            assertEquals(REASON_DELETED, row.get("revoke_reason"));
            assertEquals(1, apRevokeAudits(ap).size());
        }
    }

    // ================================================================== T5

    @Test
    @DisplayName("T5: toggleActive chiều inactive -> active không thu hồi AP, không ghi audit AP")
    void activate_doesNotRevoke() throws Exception {
        OffsetDateTime now = now();
        User target = user("t5", Role.NORMAL_USER, 1, null, false);
        AreaAssignedPersonnel active = ap(area("t5-active", AreaLevel.CONFIDENTIAL), target, now.minusDays(1), null, false);

        toggle(target, true);

        assertNull(apRow(active).get("revoked_at"));
        assertEquals(0, auditCountOf(active));
    }

    // ================================================================== T7

    @Test
    @DisplayName("T7 (BR-AP-04): sau khi vô hiệu hoá user, FM gán AP mới -> 400 ERR_AP_006, không tạo bản ghi")
    void assignAfterDeactivation_isRejected() throws Exception {
        User target = user("t7", Role.NORMAL_USER, 1, null, true);
        Area area = area("t7", AreaLevel.CONFIDENTIAL);
        toggle(target, false);

        MvcResult r = send(post("/api/areas/{areaId}/assigned-personnel", area.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + target.getId() + "\",\"reason\":\"Gán lại sau khi tài khoản bị vô hiệu hoá\"}"), fm);

        assertEquals(400, r.getResponse().getStatus(), describe(r));
        assertTrue(describe(r).contains("ERR_AP_006"), describe(r));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM area_assigned_personnel WHERE user_id = ?", Integer.class, target.getId()));
    }
}
