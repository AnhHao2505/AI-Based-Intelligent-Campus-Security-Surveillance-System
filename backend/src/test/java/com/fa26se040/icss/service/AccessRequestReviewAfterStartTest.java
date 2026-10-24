package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
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
import org.springframework.http.MediaType;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * D-1 (giống BR-GV-12 của lượt khách): đơn PENDING đã tới giờ bắt đầu (now >= startTime) thì FM không duyệt và không
 * từ chối được nữa -> 409 ERR_AC_012, đơn giữ PENDING (job chuyển EXPIRED), không audit, không thông báo.
 * Điều kiện thời gian nằm trong câu UPDATE có điều kiện (reviewIfPending), không chỉ ở bước kiểm trước.
 */
class AccessRequestReviewAfterStartTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final String REJECT_REASON = "Khu vực đang bảo trì định kỳ";

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User fm;
    private User requester;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa D1")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa D1").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa D1", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        requester = user("req", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("D1 contact " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
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
                .userCode("D1-" + tag.toUpperCase() + "-" + suffix)
                .fullName("D1 " + tag + " " + suffix)
                .email("d1-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private AccessRequest pending(OffsetDateTime startAt) {
        OffsetDateTime start = startAt.truncatedTo(ChronoUnit.SECONDS);
        return accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn D1 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(3))
                .status(RequestStatus.PENDING)
                .build());
    }

    private MvcResult review(AccessRequest r, String json) throws Exception {
        return mockMvc.perform(patch("/api/access-requests/{id}/review", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(fm))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult approve(AccessRequest r) throws Exception {
        return review(r, "{\"status\":\"APPROVED\"}");
    }

    private MvcResult reject(AccessRequest r) throws Exception {
        return review(r, "{\"status\":\"REJECTED\",\"rejectionReason\":\"" + REJECT_REASON + "\"}");
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private int reviewAudits(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action IN ('APPROVE', 'REJECT') AND target_id = ?",
                Integer.class, r.getId().toString());
    }

    private int reviewNotifications(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type IN ('REQUEST_APPROVED', 'REQUEST_REJECTED') AND reference_id = ?",
                Integer.class, r.getId());
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertBlockedAfterStart(AccessRequest r, MvcResult res) throws Exception {
        assertEquals(409, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("ERR_AC_012"), body(res));
        assertTrue(body(res).contains("Đã tới giờ bắt đầu, không thể duyệt hoặc từ chối yêu cầu."), body(res));
        assertEquals("PENDING", status(r));
        assertEquals(0, reviewAudits(r));
        assertEquals(0, reviewNotifications(r));
    }

    @Test
    @DisplayName("D-1: duyệt đơn PENDING đã qua giờ bắt đầu -> 409 ERR_AC_012, đơn giữ PENDING, không audit, không thông báo")
    void approve_afterStart_conflict() throws Exception {
        AccessRequest r = pending(OffsetDateTime.now().minusMinutes(10));

        assertBlockedAfterStart(r, approve(r));
    }

    @Test
    @DisplayName("D-1: từ chối đơn PENDING đã qua giờ bắt đầu -> 409 ERR_AC_012, đơn giữ PENDING, không audit, không thông báo")
    void reject_afterStart_conflict() throws Exception {
        AccessRequest r = pending(OffsetDateTime.now().minusMinutes(10));

        assertBlockedAfterStart(r, reject(r));
    }

    @Test
    @DisplayName("D-1: duyệt / từ chối đơn chưa tới giờ bắt đầu -> 200 như cũ, 1 audit, có thông báo")
    void review_beforeStart_ok() throws Exception {
        AccessRequest toApprove = pending(OffsetDateTime.now().plusHours(1));
        AccessRequest toReject = pending(OffsetDateTime.now().plusHours(1));

        MvcResult approved = approve(toApprove);
        MvcResult rejected = reject(toReject);

        assertEquals(200, approved.getResponse().getStatus(), body(approved));
        assertEquals("APPROVED", status(toApprove));
        assertEquals(1, reviewAudits(toApprove));
        assertEquals(1, reviewNotifications(toApprove));
        assertEquals(200, rejected.getResponse().getStatus(), body(rejected));
        assertEquals("REJECTED", status(toReject));
        assertEquals(1, reviewAudits(toReject));
        assertEquals(1, reviewNotifications(toReject));
    }

    @Test
    @DisplayName("D-1 (chống race): câu UPDATE reviewIfPending tự chặn khi now >= startTime -> 0 dòng, đơn giữ PENDING; trước giờ -> 1 dòng")
    void reviewIfPending_conditionInsideUpdate() {
        AccessRequest started = pending(OffsetDateTime.now().minusMinutes(1));
        AccessRequest notStarted = pending(OffsetDateTime.now().plusMinutes(30));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer startedRows = tx.execute(s -> accessRequestRepository.reviewIfPending(started.getId(),
                RequestStatus.APPROVED, fm, OffsetDateTime.now(), null, RequestStatus.PENDING));
        // Đúng giờ bắt đầu (now == startTime) cũng bị chặn
        Integer exactRows = tx.execute(s -> accessRequestRepository.reviewIfPending(notStarted.getId(),
                RequestStatus.APPROVED, fm, notStarted.getStartTime(), null, RequestStatus.PENDING));
        Integer okRows = tx.execute(s -> accessRequestRepository.reviewIfPending(notStarted.getId(),
                RequestStatus.APPROVED, fm, OffsetDateTime.now(), null, RequestStatus.PENDING));

        assertEquals(0, startedRows);
        assertEquals("PENDING", status(started));
        assertEquals(0, exactRows);
        assertEquals(1, okRows);
        assertEquals("APPROVED", status(notStarted));
    }
}
