package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
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
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-RQ-47 (B-03 bổ sung): FM huỷ đơn APPROVED chưa bắt đầu qua PATCH /api/access-requests/{id}/cancel-approved.
 * Lý do 10–500 ký tự; đơn CANCELLED (cancel_source STAFF từ V73, cancelled_by = FM, cancel_reason); audit CANCEL kèm lý do;
 * REQUEST_CANCELLED cho người tạo + thành viên. Đã bắt đầu -> 400 ERR_AC_008. NORMAL_USER / ADMIN không huỷ được đơn APPROVED.
 */
class AccessRequestStaffCancelTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessRequestService accessRequestService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User admin;
    private User fm;
    private User guard;
    private User requester;
    private User otherUser;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa RQ47")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa RQ47").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa RQ47", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN);
        fm = user("fm", Role.FACILITY_MANAGER);
        guard = user("grd", Role.GUARD);
        requester = user("req", Role.NORMAL_USER);
        otherUser = user("oth", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("RQ47 contact " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
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

    private User user(String tag, Role role) {
        User u = userRepository.save(User.builder()
                .userCode("RQ47-" + tag.toUpperCase() + "-" + suffix)
                .fullName("RQ47 " + tag + " " + suffix)
                .email("rq47-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private static final String REASON = "Khu vực phải đóng để bảo trì đột xuất";

    private AccessRequest request(RequestStatus status) {
        return request(status, OffsetDateTime.now().minusHours(1), List.of());
    }

    private AccessRequest request(RequestStatus status, OffsetDateTime startAt, List<User> members) {
        OffsetDateTime start = startAt.truncatedTo(ChronoUnit.SECONDS);
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(members.isEmpty() ? RequestType.INDIVIDUAL : RequestType.GROUP)
                .purpose("Đơn RQ47 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(3))
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : start.minusHours(1))
                .build();
        for (User m : members) {
            req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(m).build());
        }
        return accessRequestRepository.save(req);
    }

    private MvcResult cancelApproved(User actor, AccessRequest r) throws Exception {
        return cancelApproved(actor, r, "{\"reason\":\"" + REASON + "\"}");
    }

    private MvcResult cancelApproved(User actor, AccessRequest r, String json) throws Exception {
        return mockMvc.perform(patch("/api/access-requests/{id}/cancel-approved", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private int cancelNotifications(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'REQUEST_CANCELLED' AND reference_id = ?",
                Integer.class, r.getId());
    }

    private int cancelAudits(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'CANCEL' AND target_id = ?",
                Integer.class, r.getId().toString());
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private OffsetDateTime future() {
        return OffsetDateTime.now().plusHours(3);
    }

    @Test
    @DisplayName("FM huỷ đơn nhóm APPROVED chưa bắt đầu -> 200 CANCELLED, nguồn STAFF + người huỷ FM + lý do, 1 audit CANCEL có lý do, 2 thông báo REQUEST_CANCELLED")
    void fm_cancelApprovedNotStarted_ok() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, future(), List.of(otherUser));

        MvcResult res = cancelApproved(fm, r, "{\"reason\":\"  " + REASON + "  \"}");

        assertEquals(200, res.getResponse().getStatus(), body(res));
        assertEquals("CANCELLED", status(r));
        var row = jdbc.queryForMap("SELECT cancel_source, cancelled_by, cancel_reason FROM access_requests WHERE id = ?", r.getId());
        assertEquals("STAFF", row.get("cancel_source"));
        assertEquals(fm.getId(), row.get("cancelled_by"));
        assertEquals(REASON, row.get("cancel_reason"));
        assertEquals(1, cancelAudits(r));
        assertEquals(REASON, jdbc.queryForObject(
                "SELECT reason FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'CANCEL' AND target_id = ?",
                String.class, r.getId().toString()));
        assertEquals(2, cancelNotifications(r));
        for (User u : List.of(requester, otherUser)) {
            String msg = jdbc.queryForObject(
                    "SELECT message FROM notifications WHERE type = 'REQUEST_CANCELLED' AND reference_id = ? AND recipient_id = ?",
                    String.class, r.getId(), u.getId());
            assertTrue(msg.contains(REASON), msg);
        }
    }

    @Test
    @DisplayName("Người tạo tự huỷ đơn PENDING qua /cancel -> cancel_source USER (không phải STAFF), cancelled_by = người tạo; response trả USER")
    void requesterCancelPending_sourceUser() throws Exception {
        AccessRequest r = request(RequestStatus.PENDING, future(), List.of());

        MvcResult res = mockMvc.perform(patch("/api/access-requests/{id}/cancel", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(requester))).andReturn();

        assertEquals(200, res.getResponse().getStatus(), body(res));
        var row = jdbc.queryForMap("SELECT cancel_source, cancelled_by FROM access_requests WHERE id = ?", r.getId());
        assertEquals("USER", row.get("cancel_source"));
        assertEquals(requester.getId(), row.get("cancelled_by"));
        assertTrue(body(res).contains("\"cancelSource\":\"USER\""), body(res));
    }

    @Test
    @DisplayName("Response của FM huỷ đơn đã duyệt trả cancelSource STAFF (FE hiện nhãn \"Quản lý huỷ\")")
    void fm_cancelApproved_responseSourceStaff() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, future(), List.of());

        MvcResult res = cancelApproved(fm, r);

        assertEquals(200, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("\"cancelSource\":\"STAFF\""), body(res));
    }

    @Test
    @DisplayName("Đơn đã bắt đầu -> 400 ERR_AC_008, đơn giữ APPROVED, không audit, không thông báo")
    void fm_cancelStarted_rejected() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        MvcResult res = cancelApproved(fm, r);

        assertEquals(400, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("ERR_AC_008"), body(res));
        assertEquals("APPROVED", status(r));
        assertEquals(0, cancelAudits(r));
        assertEquals(0, cancelNotifications(r));
    }

    @Test
    @DisplayName("Đơn PENDING -> 400 (dùng Từ chối), đơn giữ PENDING")
    void fm_cancelPending_badRequest() throws Exception {
        AccessRequest r = request(RequestStatus.PENDING, future(), List.of());

        MvcResult res = cancelApproved(fm, r);

        assertEquals(400, res.getResponse().getStatus(), body(res));
        assertEquals("PENDING", status(r));
    }

    @Test
    @DisplayName("Đơn đã CANCELLED -> 409 (đã có người xử lý)")
    void fm_cancelAlreadyCancelled_conflict() throws Exception {
        AccessRequest r = request(RequestStatus.CANCELLED, future(), List.of());

        assertEquals(409, cancelApproved(fm, r).getResponse().getStatus());
    }

    @Test
    @DisplayName("Lý do thiếu / < 10 ký tự sau trim / > 500 ký tự -> 400 VALIDATION_ERROR, đơn giữ APPROVED")
    void fm_cancelInvalidReason_validationError() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, future(), List.of());

        for (String json : List.of("{}", "{\"reason\":\"   ngắn    \"}", "{\"reason\":\"" + "a".repeat(501) + "\"}")) {
            MvcResult res = cancelApproved(fm, r, json);
            assertEquals(400, res.getResponse().getStatus(), json + ": " + body(res));
            assertTrue(body(res).contains("VALIDATION_ERROR"), body(res));
        }
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("Quyền: requester / NORMAL_USER khác / ADMIN / GUARD gọi cancel-approved -> 403; requester gọi /cancel với đơn APPROVED -> 409; đơn giữ APPROVED")
    void nonFm_cannotCancelApproved() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, future(), List.of());

        for (User actor : List.of(requester, otherUser, admin, guard)) {
            MvcResult res = cancelApproved(actor, r);
            assertEquals(403, res.getResponse().getStatus(), actor.getRole() + ": " + body(res));
        }
        MvcResult own = mockMvc.perform(patch("/api/access-requests/{id}/cancel", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(requester))).andReturn();
        assertEquals(409, own.getResponse().getStatus(), body(own));
        assertEquals("APPROVED", status(r));
        assertEquals(0, cancelNotifications(r));
    }

    @Test
    @DisplayName("Service khớp controller: requester / ADMIN gọi thẳng service -> AccessDeniedException; đã bắt đầu -> ERR_AC_008")
    void service_rules() {
        AccessRequest notStarted = request(RequestStatus.APPROVED, future(), List.of());
        assertThrows(AccessDeniedException.class, () -> accessRequestService.cancelApprovedRequest(notStarted.getId(), REASON, requester.getEmail()));
        assertThrows(AccessDeniedException.class, () -> accessRequestService.cancelApprovedRequest(notStarted.getId(), REASON, admin.getEmail()));
        assertEquals("APPROVED", status(notStarted));

        AccessRequest started = request(RequestStatus.APPROVED);
        AccessControlException ex = assertThrows(AccessControlException.class,
                () -> accessRequestService.cancelApprovedRequest(started.getId(), REASON, fm.getEmail()));
        assertEquals(AccessControlErrorCode.ERR_AC_008, ex.getErrorCode());
    }
}
