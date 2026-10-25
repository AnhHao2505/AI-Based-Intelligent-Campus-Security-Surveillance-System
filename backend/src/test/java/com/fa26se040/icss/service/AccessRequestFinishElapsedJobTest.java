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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * D-2.1: job hệ thống chuyển đơn APPROVED đã qua giờ kết thúc (end_time <= now) sang FINISHED:
 * finish_reason = "Hết thời gian hiệu lực", finished_by NULL, 1 audit ACCESS_REQUEST / FINISH người thực hiện System,
 * không gửi thông báo. Đơn đang trong giờ, PENDING, REJECTED không đổi.
 * Job chạy trên DB test dùng chung nên các test không race chạy trong transaction rồi rollback.
 */
class AccessRequestFinishElapsedJobTest extends AbstractIntegrationTest {

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

    private static final String FM_REASON = "Sự kiện kết thúc sớm hơn dự kiến";
    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(10);

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User fm;
    private User requester;
    private User member;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa D2")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa D2").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa D2", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        requester = user("req", Role.NORMAL_USER);
        member = user("mem", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("D2 contact " + suffix)
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
                .userCode("D2-" + tag.toUpperCase() + "-" + suffix)
                .fullName("D2 " + tag + " " + suffix)
                .email("d2-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private AccessRequest request(RequestStatus status, OffsetDateTime startAt, OffsetDateTime endAt) {
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.GROUP)
                .purpose("Đơn D2 " + suffix)
                .startTime(startAt.truncatedTo(ChronoUnit.SECONDS))
                .endTime(endAt.truncatedTo(ChronoUnit.SECONDS))
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : startAt.minusHours(1))
                .rejectionReason(status == RequestStatus.REJECTED ? "Khu vực đang bảo trì định kỳ" : null)
                .build();
        req.getMembers().add(com.fa26se040.icss.entity.AccessRequestMember.builder().accessRequest(req).user(member).build());
        return accessRequestRepository.save(req);
    }

    private AccessRequest elapsedApproved() {
        OffsetDateTime now = OffsetDateTime.now();
        return request(RequestStatus.APPROVED, now.minusHours(4), now.minusHours(1));
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private Map<String, Object> finishColumns(AccessRequest r) {
        return jdbc.queryForMap("SELECT finish_reason, finished_by FROM access_requests WHERE id = ?", r.getId());
    }

    private List<Map<String, Object>> finishAudits(AccessRequest r) {
        return jdbc.queryForList("SELECT actor_type, changed_by, actor_source, reason, new_value ->> 'status' AS new_status "
                + "FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH' AND target_id = ?", r.getId().toString());
    }

    private int notifications(AccessRequest r) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id = ?", Integer.class, r.getId());
    }

    /** Chạy job + kiểm tra trong một transaction rồi rollback: không để lại thay đổi trên DB test dùng chung. */
    private void inRolledBackTransaction(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            body.run();
            tx.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("D-2.1: đơn APPROVED đã qua endTime -> FINISHED, finished_by NULL, lý do 'Hết thời gian hiệu lực', 1 audit System, 0 thông báo")
    void approvedElapsed_finishedBySystem() {
        AccessRequest r = elapsedApproved();

        inRolledBackTransaction(() -> {
            int count = accessRequestService.finishElapsedApprovedRequests();

            assertTrue(count >= 1, "job phải kết thúc ít nhất đơn của test: " + count);
            assertEquals("FINISHED", status(r));
            Map<String, Object> cols = finishColumns(r);
            assertEquals(AccessRequestService.FINISH_REASON_ELAPSED, cols.get("finish_reason"));
            assertEquals("Hết thời gian hiệu lực", cols.get("finish_reason"));
            assertNull(cols.get("finished_by"));
            List<Map<String, Object>> audits = finishAudits(r);
            assertEquals(1, audits.size(), audits.toString());
            assertEquals("SYSTEM", audits.get(0).get("actor_type"));
            assertNull(audits.get(0).get("changed_by"));
            assertEquals("FINISH_ELAPSED_REQUESTS_JOB", audits.get(0).get("actor_source"));
            assertEquals(AccessRequestService.FINISH_REASON_ELAPSED, audits.get(0).get("reason"));
            assertEquals("FINISHED", audits.get(0).get("new_status"));
            assertEquals(0, notifications(r));
        });
    }

    @Test
    @DisplayName("D-2.1: đơn APPROVED còn trong giờ, đơn APPROVED chưa bắt đầu, đơn PENDING / REJECTED đã qua giờ -> không đổi, không audit")
    void otherRequests_unchanged() {
        OffsetDateTime now = OffsetDateTime.now();
        AccessRequest inProgress = request(RequestStatus.APPROVED, now.minusHours(1), now.plusHours(1));
        AccessRequest notStarted = request(RequestStatus.APPROVED, now.plusHours(1), now.plusHours(3));
        AccessRequest pending = request(RequestStatus.PENDING, now.minusHours(4), now.minusHours(1));
        AccessRequest rejected = request(RequestStatus.REJECTED, now.minusHours(4), now.minusHours(1));

        inRolledBackTransaction(() -> {
            accessRequestService.finishElapsedApprovedRequests();

            assertEquals("APPROVED", status(inProgress));
            assertEquals("APPROVED", status(notStarted));
            assertEquals("PENDING", status(pending));
            assertEquals("REJECTED", status(rejected));
            for (AccessRequest r : List.of(inProgress, notStarted, pending, rejected)) {
                assertTrue(finishAudits(r).isEmpty(), r.getId().toString());
                assertNull(finishColumns(r).get("finish_reason"));
            }
        });
    }

    @Test
    @DisplayName("D-2.1: đơn APPROVED có endTime đúng bằng now -> FINISHED (end_time <= now)")
    void approvedEndingExactlyNow_finished() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        AccessRequest r = request(RequestStatus.APPROVED, now.minusHours(2), now);

        inRolledBackTransaction(() -> {
            accessRequestService.finishElapsedApprovedRequests();
            assertEquals("FINISHED", status(r));
        });
    }

    // ------------------------------------------------------------------ race với FM hoàn thành

    private MvcResult finish(User actor, AccessRequest r) throws Exception {
        return mockMvc.perform(patch("/api/access-requests/{id}/finish", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + FM_REASON + "\"}")).andReturn();
    }

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
     * {@code holder} chạy trong giao dịch giữ khoá dòng đơn (chưa commit); {@code contender} chạy ở luồng khác và phải
     * chờ khoá đó. Giao dịch holder commit xong thì contender chạy tiếp. Trả kết quả của contender.
     */
    private <T> T raceWhileHolding(Runnable holder, java.util.concurrent.Callable<T> contender) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch commitHolder = new CountDownLatch(1);
        int[] holderPid = new int[1];
        try {
            Future<?> holding = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                holder.run();
                holderPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                held.countDown();
                try {
                    assertTrue(commitHolder.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(held.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "giao dịch giữ khoá chưa chạy xong phần ghi");

            Future<T> contending = pool.submit(contender);
            boolean blocked = waitUntil(() -> sessionsBlockedBy(holderPid[0]) > 0);

            commitHolder.countDown();
            holding.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            T result = contending.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(blocked, "bên thứ hai không chờ khoá dòng đơn");
            return result;
        } finally {
            commitHolder.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Race: job đang kết thúc đơn (chưa commit) thì FM bấm Hoàn thành -> FM nhận 409, đơn FINISHED do hệ thống, 1 audit, 0 thông báo")
    void race_jobFirst_fmGetsConflict() throws Exception {
        AccessRequest r = elapsedApproved();

        MvcResult res = raceWhileHolding(
                () -> accessRequestService.finishElapsedApprovedRequests(),
                () -> finish(fm, r));

        assertEquals(409, res.getResponse().getStatus(), res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FINISHED", status(r));
        Map<String, Object> cols = finishColumns(r);
        assertEquals(AccessRequestService.FINISH_REASON_ELAPSED, cols.get("finish_reason"));
        assertNull(cols.get("finished_by"));
        List<Map<String, Object>> audits = finishAudits(r);
        assertEquals(1, audits.size(), audits.toString());
        assertEquals("SYSTEM", audits.get(0).get("actor_type"));
        assertEquals(0, notifications(r));
    }

    @Test
    @DisplayName("Race: FM đang hoàn thành đơn (chưa commit) thì job chạy -> job bỏ qua đơn này, chỉ 1 audit của FM, finished_by = FM")
    void race_fmFirst_jobSkipsRequest() throws Exception {
        AccessRequest r = elapsedApproved();

        raceWhileHolding(
                () -> accessRequestService.finishRequest(r.getId(), FM_REASON, fm.getEmail()),
                () -> accessRequestService.finishElapsedApprovedRequests());

        assertEquals("FINISHED", status(r));
        Map<String, Object> cols = finishColumns(r);
        assertEquals(FM_REASON, cols.get("finish_reason"));
        assertEquals(fm.getId(), cols.get("finished_by"));
        List<Map<String, Object>> audits = finishAudits(r);
        assertEquals(1, audits.size(), audits.toString());
        assertEquals("USER", audits.get(0).get("actor_type"));
        assertEquals(fm.getId(), audits.get(0).get("changed_by"));
    }
}
