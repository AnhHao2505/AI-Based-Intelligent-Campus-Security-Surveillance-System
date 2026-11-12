package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AssignedPersonnelErrorCode;
import com.fa26se040.icss.exception.AssignedPersonnelException;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-04: hai FM cùng thu hồi một bản ghi nhân sự chỉ định. Trước đây cả hai qua kiểm {@code revokedAt == null},
 * cùng {@code save}, ghi 2 audit REVOKE. Nay UPDATE có điều kiện {@code revoked_at IS NULL}: đúng 1 bên thắng,
 * bên còn lại nhận ERR_AP_009, đúng 1 audit. Thu hồi thủ công không gửi thông báo (code hiện tại) — vẫn 0.
 */
class AssignedPersonnelRevokeRaceTest extends AbstractIntegrationTest {

    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private AreaAssignedPersonnelService assignedPersonnelService;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User fm1;
    private User fm2;
    private User lecturer;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa AP04")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa AP04").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa AP04", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm1 = user("fm1", Role.FACILITY_MANAGER);
        fm2 = user("fm2", Role.FACILITY_MANAGER);
        lecturer = user("gv", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("AP04 khu " + suffix)
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
                .userCode("AP04-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AP04 " + tag + " " + suffix)
                .email("ap04-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private AreaAssignedPersonnel activeAssignment() {
        return assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(lecturer)
                .validFrom(OffsetDateTime.now().minusHours(1))
                .validTo(OffsetDateTime.now().plusDays(1))
                .createdBy(fm1)
                .build());
    }

    private int revokeAudits(UUID apId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?",
                Integer.class, apId.toString());
    }

    private int notifications(UUID apId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id = ?", Integer.class, apId);
    }

    /** null = thành công; khác null = mã lỗi AP. */
    private Callable<String> revokeCall(AreaAssignedPersonnel ap, User actor, String reason, CountDownLatch start) {
        return () -> {
            assertTrue(start.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            try {
                assignedPersonnelService.revoke(area.getId(), ap.getId(), new AssignedPersonnelRevokeRequest(reason), actor.getEmail());
                return null;
            } catch (AssignedPersonnelException e) {
                return e.getErrorCode().getCode();
            }
        };
    }

    @RepeatedTest(5)
    @DisplayName("Hai FM bấm thu hồi cùng lúc (2 luồng thật) -> 1 thành công, 1 ERR_AP_009, đúng 1 audit REVOKE, 0 thông báo")
    void twoFmsRevokeAtOnce_exactlyOneWins() throws Exception {
        AreaAssignedPersonnel ap = activeAssignment();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> a = pool.submit(revokeCall(ap, fm1, "FM1 thu hồi do nghỉ phép dài", start));
            Future<String> b = pool.submit(revokeCall(ap, fm2, "FM2 thu hồi do chuyển công tác", start));
            start.countDown();
            String ra = a.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String rb = b.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            List<String> results = new ArrayList<>();
            results.add(ra);
            results.add(rb);
            assertEquals(1, results.stream().filter(java.util.Objects::isNull).count(), "kết quả: " + results);
            assertTrue(results.contains(AssignedPersonnelErrorCode.ERR_AP_009.getCode()), "kết quả: " + results);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, revokeAudits(ap.getId()));
        assertEquals(0, notifications(ap.getId()));
        UUID revokedBy = jdbc.queryForObject("SELECT revoked_by FROM area_assigned_personnel WHERE id = ?", UUID.class, ap.getId());
        UUID auditActor = jdbc.queryForObject(
                "SELECT changed_by FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?",
                UUID.class, ap.getId().toString());
        assertEquals(revokedBy, auditActor, "audit phải ghi đúng người thắng");
    }

    @Test
    @DisplayName("FM2 đọc lúc AP chưa thu hồi, FM1 đang giữ dòng chưa commit -> FM2 chờ ở UPDATE rồi nhận ERR_AP_009, không ghi đè lý do")
    void revokeWhileAnotherTransactionHoldsTheRow_secondGetsConflict() throws Exception {
        AreaAssignedPersonnel ap = activeAssignment();
        String winnerReason = "FM1 thu hồi trước, giao dịch còn mở";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch rowChanged = new CountDownLatch(1);
        CountDownLatch commitOther = new CountDownLatch(1);
        int[] otherPid = new int[1];
        try {
            Future<?> other = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertEquals(1, assignedPersonnelRepository.revokeIfNotRevoked(ap.getId(), OffsetDateTime.now(), fm1, winnerReason));
                otherPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                rowChanged.countDown();
                try {
                    assertTrue(commitOther.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(rowChanged.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));

            CountDownLatch go = new CountDownLatch(1);
            Future<String> second = pool.submit(revokeCall(ap, fm2, "FM2 thu hồi sau", go));
            go.countDown();
            boolean blocked = waitUntil(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, otherPid[0]) > 0);

            commitOther.countDown();
            other.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String result = second.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertTrue(blocked, "lệnh thu hồi thứ hai không chờ khoá dòng ở câu UPDATE (kết quả " + result + ")");
            assertEquals(AssignedPersonnelErrorCode.ERR_AP_009.getCode(), result);
        } finally {
            commitOther.countDown();
            pool.shutdownNow();
        }

        assertEquals(0, revokeAudits(ap.getId()), "bên thua không ghi audit");
        assertEquals(winnerReason, jdbc.queryForObject(
                "SELECT revoke_reason FROM area_assigned_personnel WHERE id = ?", String.class, ap.getId()));
        assertEquals(fm1.getId(), jdbc.queryForObject(
                "SELECT revoked_by FROM area_assigned_personnel WHERE id = ?", UUID.class, ap.getId()));
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
