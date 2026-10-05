package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
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

/**
 * BR-RQ-SP-05: tài khoản bị vô hiệu hoá / xoá -> hệ thống huỷ đơn truy cập người đó là NGƯỜI TẠO (PENDING / APPROVED,
 * end_time > now), lý do "Người tạo đơn không còn hoạt động", thông báo thành viên, audit từng đơn.
 * Không @Transactional: fixture được commit để thông báo (REQUIRES_NEW, gửi sau commit) ghi được thật vào DB.
 * User fixture bị vô hiệu hoá (qua repository, không qua service) ở @AfterEach để không nhận thông báo của test khác.
 */
class RequestCancelOnRequesterDeactivationTest extends AbstractIntegrationTest {

    private static final String REASON = "Người tạo đơn không còn hoạt động";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private AccessDecisionService accessDecisionService;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User admin;
    private User fm;
    private User requester;
    private User memberL1;
    private User memberL2;
    private Area contactArea;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa BR4")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa BR4").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa BR4", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN, 3, null);
        fm = user("fm", Role.FACILITY_MANAGER, 3, null);
        requester = user("req", Role.NORMAL_USER, 2, null);
        memberL1 = user("m1", Role.NORMAL_USER, 1, null);
        memberL2 = user("m2", Role.NORMAL_USER, 2, null);

        contactArea = areaRepository.save(Area.builder()
                .name("BR4 contact " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    @AfterEach
    void deactivateFixtureUsers() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, int level, UUID importBatchId) {
        User u = userRepository.save(User.builder()
                .userCode("BR4-" + tag.toUpperCase() + "-" + suffix)
                .fullName("BR4 " + tag + " " + suffix)
                .email("br4-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .importBatchId(importBatchId)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    /** Đơn nhóm của requester với memberL1 (được bảo lãnh) + memberL2. */
    private AccessRequest groupRequest(User owner, RequestStatus status, OffsetDateTime start, OffsetDateTime end) {
        AccessRequest req = AccessRequest.builder()
                .area(contactArea)
                .requester(owner)
                .requestType(RequestType.GROUP)
                .purpose("Đơn nhóm BR4 " + suffix)
                .startTime(start)
                .endTime(end)
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : OffsetDateTime.now().minusHours(2))
                .rejectionReason(status == RequestStatus.REJECTED ? "Từ chối để thử BR4" : null)
                .build();
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(memberL1).sponsored(true).build());
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(memberL2).sponsored(false).build());
        return accessRequestRepository.save(req);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private MvcResult send(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin))).andReturn();
    }

    private String describe(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void deactivate(User u) throws Exception {
        MvcResult r = send(patch("/api/users/{id}/toggle-active", u.getId()));
        assertEquals(200, r.getResponse().getStatus(), describe(r));
        assertEquals(false, jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, u.getId()));
    }

    private Map<String, Object> row(AccessRequest r) {
        return jdbc.queryForMap("SELECT status, cancel_source, cancel_reason, cancelled_by FROM access_requests WHERE id = ?", r.getId());
    }

    private int systemCancelNotifs(User recipient, AccessRequest r) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'REQUEST_SYSTEM_CANCELLED' AND reference_id = ?",
                Integer.class, recipient.getId(), r.getId());
    }

    private void assertSystemCancelled(AccessRequest r) {
        Map<String, Object> row = row(r);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals("SYSTEM", row.get("cancel_source"));
        assertEquals(REASON, row.get("cancel_reason"));
        assertNull(row.get("cancelled_by"));
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT actor_type, actor_source, reason FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'CANCEL' AND target_id = ?",
                r.getId().toString());
        assertEquals(1, audits.size(), "mỗi đơn 1 audit");
        assertEquals("SYSTEM", audits.get(0).get("actor_type"));
        assertEquals("REQUESTER_DEACTIVATION", audits.get(0).get("actor_source"));
        assertEquals(REASON, audits.get(0).get("reason"));
        assertEquals(1, systemCancelNotifs(memberL1, r), "thành viên được bảo lãnh nhận thông báo");
        assertEquals(1, systemCancelNotifs(memberL2, r), "thành viên còn lại nhận thông báo");
    }

    // ================================================================== 1, 7

    @Test
    @DisplayName("#1 + #7: đơn nhóm APPROVED đang hiệu lực (có thành viên bảo lãnh) -> vô hiệu hoá requester: CANCELLED, lý do, thông báo, audit; checkEntry thành viên bị từ chối")
    void approvedGroupRequest_cancelledOnDeactivation_andSponsoredMemberDenied() throws Exception {
        OffsetDateTime now = now();
        AccessRequest active = groupRequest(requester, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2));

        AccessDecision before = accessDecisionService.checkEntry(memberL1.getId(), contactArea.getId(), now.plusMinutes(5));
        assertTrue(before.allowed(), "Tiền đề: thành viên được bảo lãnh vào được trước khi requester bị vô hiệu hoá — " + before.reason());

        deactivate(requester);

        assertSystemCancelled(active);
        assertEquals(0, systemCancelNotifs(requester, active), "người tạo (đã vô hiệu hoá) không cần nhận thông báo");

        // #7: trong khung giờ đơn, thành viên được bảo lãnh bị từ chối
        AccessDecision after = accessDecisionService.checkEntry(memberL1.getId(), contactArea.getId(), now.plusMinutes(5));
        assertFalse(after.allowed(), "Sau khi đơn bị huỷ, thành viên được bảo lãnh không còn vào được — " + after.reason());
    }

    // ================================================================== 2, 3

    @Test
    @DisplayName("#2 + #3: PENDING tương lai -> CANCELLED; FINISHED / REJECTED / APPROVED đã qua end_time -> không đổi")
    void pendingCancelled_endedOrClosedUntouched() throws Exception {
        OffsetDateTime now = now();
        AccessRequest pending = groupRequest(requester, RequestStatus.PENDING, now.plusDays(1), now.plusDays(1).plusHours(2));
        AccessRequest finished = groupRequest(requester, RequestStatus.FINISHED, now.minusHours(3), now.plusHours(3));
        AccessRequest rejected = groupRequest(requester, RequestStatus.REJECTED, now.plusDays(2), now.plusDays(2).plusHours(1));
        AccessRequest approvedPast = groupRequest(requester, RequestStatus.APPROVED, now.minusHours(5), now.minusHours(1));

        deactivate(requester);

        assertSystemCancelled(pending);
        assertEquals("FINISHED", row(finished).get("status"));
        assertEquals("REJECTED", row(rejected).get("status"));
        assertEquals("APPROVED", row(approvedPast).get("status"), "end_time đã qua -> không đổi");
        for (AccessRequest untouched : List.of(finished, rejected, approvedPast)) {
            assertNull(row(untouched).get("cancel_source"));
            assertEquals(0, systemCancelNotifs(memberL1, untouched));
        }
    }

    // ================================================================== 4

    @Test
    @DisplayName("#4: xoá tài khoản requester -> như #1")
    void deleteRequester_cancelsLikeDeactivation() throws Exception {
        OffsetDateTime now = now();
        AccessRequest active = groupRequest(requester, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2));

        MvcResult r = send(delete("/api/users/{id}", requester.getId()));
        assertEquals(200, r.getResponse().getStatus(), describe(r));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, requester.getId()));

        assertSystemCancelled(active);
    }

    // ================================================================== 5

    @Test
    @DisplayName("#5: vô hiệu hoá THÀNH VIÊN (không phải người tạo) -> đơn giữ nguyên")
    void deactivateMember_requestUntouched() throws Exception {
        OffsetDateTime now = now();
        AccessRequest active = groupRequest(requester, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2));

        deactivate(memberL2);

        Map<String, Object> row = row(active);
        assertEquals("APPROVED", row.get("status"));
        assertNull(row.get("cancel_source"));
        assertEquals(0, systemCancelNotifs(memberL1, active));
    }

    // ================================================================== 6

    @Test
    @DisplayName("#6: kích hoạt lại requester -> đơn vẫn CANCELLED")
    void reactivateRequester_requestStaysCancelled() throws Exception {
        OffsetDateTime now = now();
        AccessRequest active = groupRequest(requester, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2));
        deactivate(requester);
        assertEquals("CANCELLED", row(active).get("status"));

        MvcResult r = send(patch("/api/users/{id}/toggle-active", requester.getId()));
        assertEquals(200, r.getResponse().getStatus(), describe(r));
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, requester.getId()));

        Map<String, Object> row = row(active);
        assertEquals("CANCELLED", row.get("status"), "kích hoạt lại không khôi phục đơn");
        assertEquals(REASON, row.get("cancel_reason"));
        assertEquals(1, systemCancelNotifs(memberL1, active), "không gửi thêm thông báo khi kích hoạt lại");
    }

    // ================================================================== 8 (đường gỡ cả lô import)

    @Test
    @DisplayName("#8: gỡ cả lô import chứa requester -> đơn của requester CANCELLED như #1")
    void deleteImportBatch_cancelsRequestsOfBatchUsers() throws Exception {
        UUID batchId = UUID.randomUUID();
        User batchRequester = user("bat", Role.NORMAL_USER, 2, batchId);
        OffsetDateTime now = now();
        AccessRequest active = groupRequest(batchRequester, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2));

        MvcResult r = send(delete("/api/users/import-batches/{batchId}", batchId));
        assertEquals(200, r.getResponse().getStatus(), describe(r));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, batchRequester.getId()));

        assertSystemCancelled(active);
    }
}
