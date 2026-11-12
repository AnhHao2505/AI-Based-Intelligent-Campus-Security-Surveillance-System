package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.area.AreaDeactivateRequest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CancelSource;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ConcurrentReviewException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-07: hệ thống huỷ đơn (vô hiệu hoá khu, vô hiệu hoá / xoá người tạo) chạy cùng lúc FM duyệt. Trước đây hệ thống
 * đọc đơn PENDING, FM duyệt và commit xen giữa, rồi {@code cancelBySystemIfStatus(PENDING)} gặp 0 dòng và bỏ qua
 * -> đơn APPROVED sót trên khu / tài khoản đã vô hiệu hoá. Nay FM duyệt khoá chia sẻ dòng areas rồi users trước khi
 * đọc đơn, nên duyệt và huỷ hệ thống chạy tuần tự.
 */
class AccessRequestReviewSystemCancelRaceTest extends AbstractIntegrationTest {

    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEACTIVATE_REASON = "Khu vực ngừng sử dụng để bảo trì định kỳ";

    @Autowired private AccessRequestService accessRequestService;
    @Autowired private AreaService areaService;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private Floor floor;
    private User admin;
    private User fm;
    private User requester;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa BE07")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa BE07").build()));
        floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa BE07", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        admin = user("adm", Role.ADMIN);
        fm = user("fm", Role.FACILITY_MANAGER);
        requester = user("req", Role.NORMAL_USER);
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
                .userCode("BE07-" + tag.toUpperCase() + "-" + suffix)
                .fullName("BE07 " + tag + " " + suffix)
                .email("be07-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private Area area() {
        return areaRepository.save(Area.builder()
                .name("BE07 khu " + UUID.randomUUID().toString().substring(0, 8))
                .building("Tòa BE07")
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .build());
    }

    private AccessRequest pendingRequest(Area area) {
        OffsetDateTime start = OffsetDateTime.now().plusHours(2);
        return accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn BE07 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(2))
                .status(RequestStatus.PENDING)
                .build());
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private int approveAudits(AccessRequest r) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'APPROVE' AND target_id = ?",
                Integer.class, r.getId().toString());
    }

    /** "OK" / "409" / tên lỗi khác. */
    private Callable<String> approveCall(AccessRequest r, CountDownLatch start) {
        return () -> {
            assertTrue(start.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            try {
                accessRequestService.reviewRequest(r.getId(), new AccessRequestReviewRequest(RequestStatus.APPROVED, null), fm.getEmail());
                return "OK";
            } catch (ConcurrentReviewException e) {
                return "409";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        };
    }

    /**
     * Giao dịch hệ thống: khoá (lockStep), đọc đơn còn PENDING, chờ FM duyệt chạy, rồi huỷ đơn theo trạng thái đã đọc —
     * đúng trình tự của AreaService.deactivate / UserService.toggleActive (khoá -> đọc danh sách -> huỷ có điều kiện).
     * Trả số dòng huỷ được.
     */
    private int systemCancelWhileFmApproves(AccessRequest r, Consumer<AccessRequest> lockStep, String[] fmResult) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch candidatesRead = new CountDownLatch(1);
        CountDownLatch cancelNow = new CountDownLatch(1);
        int[] systemPid = new int[1];
        try {
            Future<Integer> system = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                lockStep.accept(r);
                RequestStatus seen = RequestStatus.valueOf(status(r));
                systemPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                candidatesRead.countDown();
                try {
                    assertTrue(cancelNow.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return accessRequestRepository.cancelBySystemIfStatus(r.getId(), seen, RequestStatus.CANCELLED,
                        CancelSource.SYSTEM, "Hệ thống huỷ (BE-07)", OffsetDateTime.now());
            }));
            assertTrue(candidatesRead.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));

            CountDownLatch go = new CountDownLatch(1);
            Future<String> review = pool.submit(approveCall(r, go));
            go.countDown();
            // Trước BE-07: FM không chờ gì, duyệt + commit ngay, rồi hệ thống huỷ 0 dòng
            boolean blocked = waitUntil(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, systemPid[0]) > 0);

            cancelNow.countDown();
            int cancelled = system.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            fmResult[0] = review.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(blocked, "FM duyệt không chờ khoá của giao dịch hệ thống (kết quả " + fmResult[0] + ")");
            return cancelled;
        } finally {
            cancelNow.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Vô hiệu hoá khu đang chạy (đã khoá khu, đã đọc đơn PENDING) -> FM duyệt phải chờ, rồi nhận 409; đơn CANCELLED")
    void areaDeactivationInProgress_fmApproveWaitsThenConflicts() throws Exception {
        AccessRequest r = pendingRequest(area());
        String[] fm = new String[1];

        int cancelled = systemCancelWhileFmApproves(r, req -> areaRepository.findByIdWithLock(req.getArea().getId()), fm);

        assertEquals(1, cancelled, "hệ thống phải huỷ được đơn");
        assertEquals("409", fm[0]);
        assertEquals("CANCELLED", status(r));
        assertEquals(0, approveAudits(r));
    }

    @Test
    @DisplayName("Vô hiệu hoá người tạo đang chạy (đã khoá users, đã đọc đơn PENDING) -> FM duyệt phải chờ, rồi nhận 409; đơn CANCELLED")
    void requesterDeactivationInProgress_fmApproveWaitsThenConflicts() throws Exception {
        AccessRequest r = pendingRequest(area());
        String[] fm = new String[1];

        int cancelled = systemCancelWhileFmApproves(r, req -> userRepository.lockRowById(requester.getId()), fm);

        assertEquals(1, cancelled);
        assertEquals("409", fm[0]);
        assertEquals("CANCELLED", status(r));
        assertEquals(0, approveAudits(r));
    }

    @Test
    @DisplayName("FM đang duyệt (đã khoá khu + người tạo, UPDATE APPROVED chưa commit) -> vô hiệu hoá khu (code thật) chờ, rồi huỷ đơn APPROVED")
    void fmApproveInProgress_realAreaDeactivationWaitsThenCancelsApproved() throws Exception {
        Area area = area();
        AccessRequest r = pendingRequest(area);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch approvedNotCommitted = new CountDownLatch(1);
        CountDownLatch commitReview = new CountDownLatch(1);
        int[] reviewPid = new int[1];
        try {
            Future<?> review = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                // Cùng trình tự khoá với AccessRequestService.reviewRequest
                areaRepository.lockRowForShareById(area.getId());
                userRepository.lockRowForShareById(requester.getId());
                assertEquals(1, accessRequestRepository.reviewIfPending(r.getId(), RequestStatus.APPROVED, fm,
                        OffsetDateTime.now(), null, RequestStatus.PENDING));
                reviewPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                approvedNotCommitted.countDown();
                try {
                    assertTrue(commitReview.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(approvedNotCommitted.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));

            Long version = areaRepository.findById(area.getId()).orElseThrow().getVersion();
            Future<?> deactivate = pool.submit(() -> areaService.deactivate(area.getId(),
                    new AreaDeactivateRequest(DEACTIVATE_REASON, version), admin.getEmail()));
            boolean blocked = waitUntil(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, reviewPid[0]) > 0);

            commitReview.countDown();
            review.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            deactivate.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(blocked, "vô hiệu hoá khu không chờ khoá chia sẻ của FM duyệt");
        } finally {
            commitReview.countDown();
            pool.shutdownNow();
        }

        assertEquals("CANCELLED", status(r));
        assertEquals("SYSTEM", jdbc.queryForObject("SELECT cancel_source FROM access_requests WHERE id = ?", String.class, r.getId()));
    }

    @RepeatedTest(5)
    @DisplayName("FM duyệt và ADMIN vô hiệu hoá khu bấm cùng lúc (2 luồng thật, code thật) -> đơn không bao giờ còn APPROVED")
    void approveAndDeactivateAtOnce_neverLeavesApproved() throws Exception {
        Area area = area();
        AccessRequest r = pendingRequest(area);
        Long version = areaRepository.findById(area.getId()).orElseThrow().getVersion();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> review = pool.submit(approveCall(r, start));
            Future<?> deactivate = pool.submit(() -> {
                assertTrue(start.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                areaService.deactivate(area.getId(), new AreaDeactivateRequest(DEACTIVATE_REASON, version), admin.getEmail());
                return null;
            });
            start.countDown();
            String fmResult = review.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            deactivate.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertTrue(fmResult.equals("OK") || fmResult.equals("409"), "kết quả FM: " + fmResult);
        } finally {
            pool.shutdownNow();
        }
        assertNotEquals("APPROVED", status(r));
        assertEquals("CANCELLED", status(r));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_active FROM areas WHERE id = ?", Boolean.class, area.getId()));
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
}
