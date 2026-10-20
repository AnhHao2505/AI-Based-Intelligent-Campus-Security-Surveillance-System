package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CancelSource;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-RQ-44: chỉ FACILITY_MANAGER chuyển đơn APPROVED sang FINISHED (PATCH /api/access-requests/{id}/finish).
 * Service khớp controller: requester / ADMIN gọi thẳng service cũng bị từ chối. Điều kiện trạng thái APPROVED giữ nguyên.
 * BR-RQ-46 (B-03): chỉ khi now >= startTime (trước đó ERR_AC_007), bắt buộc lý do 10–500 ký tự,
 * lý do vào audit, thông báo REQUEST_FINISHED cho người tạo + thành viên.
 */
class AccessRequestFinishTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessRequestService accessRequestService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

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
        Building building = buildingRepository.findByNameIgnoreCase("Tòa RQ44")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa RQ44").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa RQ44", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN);
        fm = user("fm", Role.FACILITY_MANAGER);
        guard = user("grd", Role.GUARD);
        requester = user("req", Role.NORMAL_USER);
        otherUser = user("oth", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("RQ44 contact " + suffix)
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
                .userCode("RQ44-" + tag.toUpperCase() + "-" + suffix)
                .fullName("RQ44 " + tag + " " + suffix)
                .email("rq44-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private static final String REASON = "Sự kiện kết thúc sớm hơn dự kiến";

    private AccessRequest request(RequestStatus status) {
        return request(status, OffsetDateTime.now().minusHours(1), List.of());
    }

    private AccessRequest request(RequestStatus status, OffsetDateTime startAt, List<User> members) {
        OffsetDateTime start = startAt.truncatedTo(ChronoUnit.SECONDS);
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(members.isEmpty() ? RequestType.INDIVIDUAL : RequestType.GROUP)
                .purpose("Đơn RQ44 " + suffix)
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

    private MvcResult finish(User actor, AccessRequest r) throws Exception {
        return finish(actor, r, "{\"reason\":\"" + REASON + "\"}");
    }

    private MvcResult finish(User actor, AccessRequest r, String json) throws Exception {
        return mockMvc.perform(patch("/api/access-requests/{id}/finish", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private int finishNotifications(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'REQUEST_FINISHED' AND reference_id = ?",
                Integer.class, r.getId());
    }

    private int finishAudits(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH' AND target_id = ?",
                Integer.class, r.getId().toString());
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(10);

    /** Số phiên đang chờ khoá do phiên {@code blockerPid} giữ (không lẫn với phiên của suite khác dùng chung DB test). */
    private int sessionsBlockedBy(int blockerPid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                Integer.class, blockerPid);
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + RACE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /**
     * Race giữa đọc-kiểm tra và ghi: một giao dịch khác ({@code otherWriter}) đã đổi dòng đơn nhưng CHƯA commit
     * khi {@code actor} gửi lệnh hoàn thành. Lệnh hoàn thành đọc đơn (vẫn APPROVED vì giao dịch kia chưa commit),
     * qua mọi kiểm tra rồi bị khoá dòng chặn ở câu UPDATE. Giao dịch kia commit xong thì lệnh hoàn thành chạy tiếp.
     */
    private MvcResult finishWhileAnotherTransactionHoldsTheRow(User actor, AccessRequest r, Runnable otherWriter)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch rowChanged = new CountDownLatch(1);
        CountDownLatch commitOther = new CountDownLatch(1);
        int[] otherPid = new int[1];
        try {
            Future<?> other = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                otherWriter.run();
                // JdbcTemplate dùng chung kết nối của giao dịch đang mở -> pid của phiên đang giữ khoá dòng đơn
                otherPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                rowChanged.countDown();
                try {
                    assertTrue(commitOther.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(rowChanged.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "giao dịch khác chưa đổi được dòng đơn");

            Future<MvcResult> finishing = pool.submit(() -> finish(actor, r));
            boolean blocked = waitUntil(() -> sessionsBlockedBy(otherPid[0]) > 0);

            commitOther.countDown();
            other.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            MvcResult res = finishing.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(blocked, "lệnh hoàn thành không chờ khoá dòng ở câu UPDATE (status " + res.getResponse().getStatus() + ")");
            return res;
        } finally {
            commitOther.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("FM hoàn thành đơn APPROVED -> 200, response FINISHED, 1 audit ACCESS_REQUEST / FINISH, 1 thông báo REQUEST_FINISHED")
    void fm_finishApproved_ok() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        MvcResult res = finish(fm, r);

        assertEquals(200, res.getResponse().getStatus(), body(res));
        assertEquals("FINISHED", objectMapper.readTree(body(res)).path("data").path("status").asText(), body(res));
        assertEquals("FINISHED", status(r));
        assertEquals(1, finishAudits(r));
        assertEquals(1, finishNotifications(r));
    }

    @Test
    @DisplayName("Requester / ADMIN / NORMAL_USER khác / GUARD gọi API -> 403, đơn giữ APPROVED")
    void nonFm_api_forbidden() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        for (User actor : List.of(requester, admin, otherUser, guard)) {
            MvcResult res = finish(actor, r);
            assertEquals(403, res.getResponse().getStatus(), actor.getRole() + ": " + body(res));
        }
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("Service khớp controller: requester / ADMIN gọi thẳng service -> AccessDeniedException, đơn giữ APPROVED")
    void nonFm_service_forbidden() {
        AccessRequest r = request(RequestStatus.APPROVED);

        assertThrows(AccessDeniedException.class, () -> accessRequestService.finishRequest(r.getId(), REASON, requester.getEmail()));
        assertThrows(AccessDeniedException.class, () -> accessRequestService.finishRequest(r.getId(), REASON, admin.getEmail()));
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("Điều kiện trạng thái giữ nguyên: FM hoàn thành đơn PENDING -> 400, đơn giữ PENDING")
    void fm_finishPending_badRequest() throws Exception {
        AccessRequest r = request(RequestStatus.PENDING);

        MvcResult res = finish(fm, r);

        assertEquals(400, res.getResponse().getStatus(), body(res));
        assertEquals("PENDING", status(r));
    }

    @Test
    @DisplayName("BR-RQ-46: FM hoàn thành đơn chưa tới giờ bắt đầu -> 400 ERR_AC_007, đơn giữ APPROVED, không audit, không thông báo")
    void fm_finishBeforeStart_rejected() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, OffsetDateTime.now().plusHours(2), List.of());

        MvcResult res = finish(fm, r);

        assertEquals(400, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("ERR_AC_007"), body(res));
        assertEquals("APPROVED", status(r));
        assertEquals(0, finishAudits(r));
        assertEquals(0, finishNotifications(r));
    }

    @Test
    @DisplayName("BR-RQ-46 (service): gọi thẳng service với đơn chưa bắt đầu -> AccessControlException ERR_AC_007")
    void service_finishBeforeStart_throws() {
        AccessRequest r = request(RequestStatus.APPROVED, OffsetDateTime.now().plusMinutes(30), List.of());

        AccessControlException ex = assertThrows(AccessControlException.class,
                () -> accessRequestService.finishRequest(r.getId(), REASON, fm.getEmail()));
        assertEquals(AccessControlErrorCode.ERR_AC_007, ex.getErrorCode());
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("BR-RQ-46: thiếu lý do / lý do < 10 ký tự sau trim / > 500 ký tự -> 400 VALIDATION_ERROR, đơn giữ APPROVED")
    void fm_finishInvalidReason_validationError() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        for (String json : List.of("{}", "{\"reason\":\"   ngắn    \"}", "{\"reason\":\"" + "a".repeat(501) + "\"}")) {
            MvcResult res = finish(fm, r, json);
            assertEquals(400, res.getResponse().getStatus(), json + ": " + body(res));
            assertTrue(body(res).contains("VALIDATION_ERROR"), body(res));
        }
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("BR-RQ-46: hoàn thành đơn nhóm -> lý do vào audit, REQUEST_FINISHED gửi người tạo + thành viên (mỗi người 1)")
    void fm_finishGroup_auditReasonAndNotifies() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED, OffsetDateTime.now().minusMinutes(10), List.of(otherUser));

        MvcResult res = finish(fm, r, "{\"reason\":\"  " + REASON + "  \"}");

        assertEquals(200, res.getResponse().getStatus(), body(res));
        assertEquals("FINISHED", status(r));
        assertEquals(REASON, jdbc.queryForObject(
                "SELECT reason FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH' AND target_id = ?",
                String.class, r.getId().toString()));
        assertEquals(2, finishNotifications(r));
        for (User u : List.of(requester, otherUser)) {
            String msg = jdbc.queryForObject(
                    "SELECT message FROM notifications WHERE type = 'REQUEST_FINISHED' AND reference_id = ? AND recipient_id = ?",
                    String.class, r.getId(), u.getId());
            assertTrue(msg.contains(REASON), msg);
        }
    }

    @Test
    @DisplayName("Race: hai FM hoàn thành cùng lúc -> FM chậm hơn nhận 409, chỉ 1 audit FINISH và 1 thông báo REQUEST_FINISHED")
    void race_twoFmsFinishAtOnce_secondGetsConflict() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);
        User otherFm = user("fm2", Role.FACILITY_MANAGER);

        MvcResult res = finishWhileAnotherTransactionHoldsTheRow(fm, r,
                () -> accessRequestService.finishRequest(r.getId(), REASON, otherFm.getEmail()));

        assertEquals(409, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("FINISHED"), body(res));
        assertEquals("FINISHED", status(r));
        assertEquals(1, finishAudits(r));
        assertEquals(1, finishNotifications(r));
        assertEquals(otherFm.getId(), jdbc.queryForObject(
                "SELECT changed_by FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH' AND target_id = ?",
                UUID.class, r.getId().toString()));
    }

    @Test
    @DisplayName("Race: hệ thống huỷ đơn (vô hiệu hoá khu vực / người tạo) xen giữa -> 409, đơn giữ CANCELLED nguồn SYSTEM, không audit FINISH, không thông báo")
    void race_systemCancelInBetween_conflictAndNoOverwrite() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        MvcResult res = finishWhileAnotherTransactionHoldsTheRow(fm, r, () -> assertEquals(1,
                accessRequestRepository.cancelBySystemIfStatus(r.getId(), RequestStatus.APPROVED, RequestStatus.CANCELLED,
                        CancelSource.SYSTEM, "Khu vực bị vô hiệu hoá", OffsetDateTime.now())));

        assertEquals(409, res.getResponse().getStatus(), body(res));
        assertTrue(body(res).contains("CANCELLED"), body(res));
        assertEquals("CANCELLED", status(r));
        assertEquals("SYSTEM", jdbc.queryForObject(
                "SELECT cancel_source FROM access_requests WHERE id = ?", String.class, r.getId()));
        assertEquals(0, finishAudits(r));
        assertEquals(0, finishNotifications(r));
    }

    @Test
    @DisplayName("Hoàn thành 2 lần liên tiếp -> lần 2 bị chặn (400 theo kiểm tra trạng thái giữ nguyên), vẫn chỉ 1 audit FINISH và 1 thông báo")
    void fm_finishTwice_secondRejected_singleAudit() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        assertEquals(200, finish(fm, r).getResponse().getStatus());
        MvcResult second = finish(fm, r);

        assertEquals(400, second.getResponse().getStatus(), body(second));
        assertEquals("FINISHED", status(r));
        assertEquals(1, finishAudits(r));
        assertEquals(1, finishNotifications(r));
    }
}
