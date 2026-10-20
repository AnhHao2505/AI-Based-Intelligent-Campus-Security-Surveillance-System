package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-AL-30 (V83): không đổi cấp (nâng hay hạ, từng người hay hàng loạt) khi người dùng là người tạo hoặc thành viên của đơn
 * PENDING -> 409 ERR_AC_011, không lưu, không audit; đơn APPROVED không chặn. Đổi cấp và tạo đơn khoá cùng dòng users nên
 * chạy tuần tự: kiểm bằng 2 giao dịch thật và pg_blocking_pids (không dựa vào sleep để sắp thứ tự).
 */
class AccessLevelPendingRequestGuardTest extends Step5bTestSupport {

    private static final String REASON = "Điều chỉnh cấp truy cập theo quyết định mới";
    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private UserService userService;
    @Autowired private AccessRequestService accessRequestService;
    @Autowired private PlatformTransactionManager transactionManager;

    private MvcResult changeLevel(User target, int level) throws Exception {
        return send(patch("/api/users/{id}/access-level", target.getId()), fm, Map.of("accessLevel", level, "reason", REASON)).andReturn();
    }

    private int dbLevel(User u) {
        return jdbcTemplate.queryForObject("SELECT access_level FROM users WHERE id = ?", Integer.class, u.getId());
    }

    private int levelAudits(User u) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' AND target_id = ?", Integer.class, u.getId().toString());
    }

    private AccessRequest pending(Area area, User requester, RequestType type, User... members) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);
        return newRequest(area, requester, type, RequestStatus.PENDING, start, start.plusHours(2), members);
    }

    private String expectedMessage(User u, int count, String areas) {
        return "Người dùng " + u.getUserCode() + " – " + u.getFullName() + " đang có " + count + " yêu cầu chờ duyệt (" + areas
                + "). Hãy duyệt hoặc từ chối các yêu cầu này trước khi đổi cấp.";
    }

    @Test
    @DisplayName("BR-AL-30: người tạo đơn PENDING -> nâng cấp và hạ cấp đều 409 ERR_AC_011, cấp giữ nguyên, không audit")
    void pendingRequester_raiseAndLower_conflict() throws Exception {
        Area highly = newArea(AreaLevel.HIGHLY_CONFIDENTIAL);
        pending(highly, userL2, RequestType.INDIVIDUAL);

        for (int level : List.of(3, 1)) {
            MvcResult r = changeLevel(userL2, level);
            assertEquals(409, status(r), describe(r));
            assertEquals("ERR_AC_011", errorCode(r));
            assertEquals(expectedMessage(userL2, 1, highly.getName()), message(r));
        }
        assertEquals(2, dbLevel(userL2));
        assertEquals(0, levelAudits(userL2));
    }

    @Test
    @DisplayName("BR-AL-30: thành viên đơn nhóm PENDING -> 409; câu nêu số đơn và tên khu (không lặp); đổi về đúng cấp hiện tại -> 200 không đổi")
    void pendingMember_conflict_listsRequestsAndAreas() throws Exception {
        Area a1 = newArea(AreaLevel.CONFIDENTIAL);
        Area a2 = newArea(AreaLevel.CONFIDENTIAL);
        pending(a1, userL3, RequestType.GROUP, userL1);
        newRequest(a2, userL1, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                OffsetDateTime.now().plusDays(2), OffsetDateTime.now().plusDays(2).plusHours(1));
        newRequest(a2, userL1, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                OffsetDateTime.now().plusDays(3), OffsetDateTime.now().plusDays(3).plusHours(1));

        MvcResult r = changeLevel(userL1, 2);
        assertEquals(409, status(r), describe(r));
        assertEquals(expectedMessage(userL1, 3, a1.getName() + ", " + a2.getName()), message(r));
        assertEquals(1, dbLevel(userL1));

        MvcResult same = changeLevel(userL1, 1);
        assertEquals(200, status(same), "đổi về đúng cấp hiện tại không phải đổi cấp (BR-AL-06): " + describe(same));
    }

    @Test
    @DisplayName("BR-AL-30: chỉ có đơn APPROVED / REJECTED / CANCELLED -> đổi cấp bình thường (200, có audit)")
    void nonPendingRequests_doNotBlock() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        newRequest(area, userL1, RequestType.INDIVIDUAL, RequestStatus.APPROVED, start, start.plusHours(1));
        newRequest(area, userL1, RequestType.INDIVIDUAL, RequestStatus.REJECTED, start.plusHours(2), start.plusHours(3));
        newRequest(area, userL1, RequestType.INDIVIDUAL, RequestStatus.CANCELLED, start.plusHours(4), start.plusHours(5));

        MvcResult r = changeLevel(userL1, 2);
        assertEquals(200, status(r), describe(r));
        assertEquals(2, dbLevel(userL1));
        assertEquals(1, levelAudits(userL1));
    }

    @Test
    @DisplayName("BR-AL-30 hàng loạt: người có đơn PENDING -> FAILED kèm câu ERR_AC_011, người khác vẫn UPDATED")
    void bulk_pendingUserFailed_othersUpdated() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        pending(area, userL1, RequestType.INDIVIDUAL);
        User free = newUser("free", com.fa26se040.icss.enums.Role.NORMAL_USER, 1, true);

        MvcResult r = send(patch("/api/users/bulk-access-level"), fm,
                Map.of("userIds", List.of(userL1.getId(), free.getId()), "accessLevel", 2, "reason", REASON)).andReturn();
        assertEquals(200, status(r), describe(r));
        JsonNode data = json(r).path("data");
        assertEquals(1, data.path("updated").asInt());
        assertEquals(1, data.path("failed").asInt());
        for (JsonNode item : data.path("results")) {
            if (item.path("userId").asText().equals(userL1.getId().toString())) {
                assertEquals("FAILED", item.path("outcome").asText());
                assertEquals(expectedMessage(userL1, 1, area.getName()), item.path("message").asText());
            } else {
                assertEquals("UPDATED", item.path("outcome").asText());
            }
        }
        assertEquals(1, dbLevel(userL1));
        assertEquals(2, dbLevel(free));
    }

    // ================================================================== race (2 giao dịch thật)

    private int sessionsBlockedBy(int blockerPid) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
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
     * Chạy {@code first} trong giao dịch giữ mở (khoá dòng users vẫn còn), gửi {@code second} ở luồng khác, xác nhận
     * {@code second} bị chặn bởi phiên của {@code first} (pg_blocking_pids), rồi mới commit {@code first}.
     * Trả kết quả của {@code second}: giá trị hoặc exception nó ném.
     */
    private Object secondWaitsForFirst(Runnable first, java.util.concurrent.Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        int[] firstPid = new int[1];
        try {
            Future<?> f1 = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                first.run();
                firstPid[0] = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                firstDone.countDown();
                try {
                    assertTrue(commitFirst.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(firstDone.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "giao dịch đầu chưa chạy xong");
            Future<Object> f2 = pool.submit(second);
            boolean blocked = waitUntil(() -> sessionsBlockedBy(firstPid[0]) > 0);

            commitFirst.countDown();
            f1.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Object result;
            try {
                result = f2.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                result = e.getCause();
            }
            assertTrue(blocked, "thao tác thứ hai không chờ khoá dòng users của giao dịch đầu: " + result);
            return result;
        } finally {
            commitFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Race BR-AL-30: tạo đơn trước (chưa commit) -> đổi cấp chờ khoá users, sau commit gặp đơn PENDING -> 409")
    void race_requestFirst_levelChangeConflicts() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);

        Object result = secondWaitsForFirst(
                () -> accessRequestService.createIndividualRequest(
                        new IndividualAccessRequestCreateRequest(area.getId(), start, start.plusHours(2), "Đơn thử race BR-AL-30"),
                        userL1.getEmail()),
                () -> userService.updateAccessLevel(userL1.getId(), 2, REASON, fm.getEmail()));

        AccessControlException ex = assertInstanceOf(AccessControlException.class, result);
        assertEquals(AccessControlErrorCode.ERR_AC_011, ex.getErrorCode());
        assertEquals(1, dbLevel(userL1));
        assertEquals(0, levelAudits(userL1));
    }

    @Test
    @DisplayName("Race BR-AL-30: đổi cấp L1 -> L2 trước (chưa commit) -> tạo đơn cá nhân vào Bảo mật chờ khoá users, sau commit kiểm theo cấp mới -> 400 đã đủ cấp")
    void race_levelChangeFirst_requestUsesNewLevel() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);
        long requestsBefore = accessRequestRepository.count();

        Object result = secondWaitsForFirst(
                () -> userService.updateAccessLevel(userL1.getId(), 2, REASON, fm.getEmail()),
                () -> accessRequestService.createIndividualRequest(
                        new IndividualAccessRequestCreateRequest(area.getId(), start, start.plusHours(2), "Đơn thử race BR-AL-30"),
                        userL1.getEmail()));

        IllegalArgumentException ex = assertInstanceOf(IllegalArgumentException.class, result);
        assertEquals("Bạn đã đủ cấp vào khu vực này, không cần tạo yêu cầu truy cập (khu vực cấp 2, bạn cấp 2)", ex.getMessage());
        assertEquals(2, dbLevel(userL1));
        assertEquals(requestsBefore, accessRequestRepository.count());
    }
}
