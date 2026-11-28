package com.fa26se040.icss.accesshistory;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class CheckEntryPerformanceRegressionTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;
    @Value("${icss.internal.service-key}") private String serviceKey;

    private String suffix;
    private Area area;
    private Camera camera;
    private User authorizedUser;
    private User assignedUser;
    private User requestUser;
    private final List<UUID> extraUserIds = new ArrayList<>();
    private final List<UUID> assignmentIds = new ArrayList<>();
    private final List<UUID> requestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Building building = buildingRepository.findByNameIgnoreCase("Benchmark Building")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Benchmark Building").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Benchmark Building", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("Benchmark Area " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("BM-CAM-" + suffix)
                .name("Benchmark Cam " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
        authorizedUser = userRepository.save(User.builder()
                .userCode("BM-AUTH-" + suffix)
                .fullName("Auth User " + suffix)
                .email("bm-auth-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build());
        assignedUser = userRepository.save(User.builder()
                .userCode("BM-AP-" + suffix)
                .fullName("AP User " + suffix)
                .email("bm-ap-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());
        AreaAssignedPersonnel ap = assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(assignedUser)
                .validFrom(OffsetDateTime.parse("2026-10-01T00:00:00+07:00"))
                .validTo(OffsetDateTime.parse("2026-10-30T00:00:00+07:00"))
                .note("Benchmark assigned personnel")
                .createdBy(authorizedUser)
                .build());
        assignmentIds.add(ap.getId());

        requestUser = userRepository.save(User.builder()
                .userCode("BM-REQ-" + suffix)
                .fullName("Req User " + suffix)
                .email("bm-req-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());
        AccessRequest req = accessRequestRepository.save(AccessRequest.builder()
                .requester(requestUser)
                .area(area)
                .requestType(RequestType.INDIVIDUAL)
                .status(RequestStatus.APPROVED)
                .startTime(OffsetDateTime.parse("2026-10-14T08:00:00+07:00"))
                .endTime(OffsetDateTime.parse("2026-10-14T18:00:00+07:00"))
                .purpose("Benchmark access request purpose")
                .build());
        requestIds.add(req.getId());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        for (UUID id : requestIds) {
            accessRequestRepository.deleteById(id);
        }
        requestIds.clear();
        for (UUID id : assignmentIds) {
            assignedPersonnelRepository.deleteById(id);
        }
        assignmentIds.clear();
        cameraRepository.deleteById(camera.getId());
        areaRepository.deleteById(area.getId());
        userRepository.deleteById(authorizedUser.getId());
        userRepository.deleteById(assignedUser.getId());
        userRepository.deleteById(requestUser.getId());
        for (UUID uid : extraUserIds) {
            userRepository.deleteById(uid);
        }
        extraUserIds.clear();
    }

    private record BenchStats(long medianMs, long p95Ms, long maxMs, double avgSqlStmts, double avgTx) {}

    private BenchStats runCase(String label, List<String> userCodes, String ts) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<Long> durationsMs = new ArrayList<>();
        long totalStmts = 0;
        long totalTx = 0;

        for (String code : userCodes) {
            stats.clear();
            String body = objectMapper.writeValueAsString(Map.of(
                    "cameraCode", camera.getCameraCode(),
                    "userCode", code,
                    "timestamp", ts
            ));
            long t0 = System.nanoTime();
            MvcResult res = mockMvc.perform(post("/api/access-control/check-entry")
                            .header(InternalServiceKeyFilter.HEADER, serviceKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            durationsMs.add(elapsedMs);
            totalStmts += stats.getPrepareStatementCount();
            totalTx += stats.getTransactionCount();
        }

        List<Long> sorted = new ArrayList<>(durationsMs);
        Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1));
        long max = sorted.get(sorted.size() - 1);
        double avgSql = (double) totalStmts / sorted.size();
        double avgTx = (double) totalTx / sorted.size();
        System.out.printf("[BENCH-SUMMARY][%s] n=%d medianMs=%d p95Ms=%d maxMs=%d avgSqlStmts=%.2f avgTx=%.2f%n",
                label, sorted.size(), median, p95, max, avgSql, avgTx);
        return new BenchStats(median, p95, max, avgSql, avgTx);
    }

    @Test
    @DisplayName("check-entry stays within SQL query budget and never deadlocks Hikari pool under concurrent requests")
    void checkEntry_sqlBudgetAndConcurrencySafe() throws Exception {
        String ts = "2026-10-14T10:00:00+07:00";

        List<String> authCodes = new ArrayList<>();
        List<String> denyCodes = new ArrayList<>();
        List<String> unknownCodes = new ArrayList<>();
        List<String> concurrentCodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            User u1 = userRepository.save(User.builder()
                    .userCode("BM-U-" + i + "-" + suffix)
                    .fullName("U " + i)
                    .email("bm-u-" + i + "-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(3)
                    .isActive(true)
                    .build());
            extraUserIds.add(u1.getId());
            authCodes.add(u1.getUserCode());

            User u2 = userRepository.save(User.builder()
                    .userCode("BM-D-" + i + "-" + suffix)
                    .fullName("D " + i)
                    .email("bm-d-" + i + "-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(1)
                    .isActive(true)
                    .build());
            extraUserIds.add(u2.getId());
            denyCodes.add(u2.getUserCode());
            unknownCodes.add("NO-USER-" + i + "-" + suffix);
        }
        for (int i = 0; i < 6; i++) {
            User uc = userRepository.save(User.builder()
                    .userCode("BM-C-" + i + "-" + suffix)
                    .fullName("C " + i)
                    .email("bm-c-" + i + "-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(3)
                    .isActive(true)
                    .build());
            extraUserIds.add(uc.getId());
            concurrentCodes.add(uc.getUserCode());
        }

        // Cold-JVM warmup (1 request) so steady-state SQL/TX budget is independent of test execution order
        User warmupUser = userRepository.save(User.builder()
                .userCode("BM-WARM-" + suffix)
                .fullName("Warmup User")
                .email("bm-warm-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build());
        extraUserIds.add(warmupUser.getId());
        runCase("COLD_WARMUP", List.of(warmupUser.getUserCode()), ts);

        BenchStats authUndebounced = runCase("AUTHORIZED_UNDEBOUNCED", authCodes, ts);
        // Warm up debounce first so debounced case measures steady-state 0-log-TX path
        runCase("AUTHORIZED_DEBOUNCE_WARMUP", List.of(authorizedUser.getUserCode()), ts);
        BenchStats authDebounced = runCase("AUTHORIZED_DEBOUNCED", Collections.nCopies(10, authorizedUser.getUserCode()), ts);
        BenchStats assigned = runCase("ASSIGNED_PERSONNEL_FIRST", List.of(assignedUser.getUserCode()), ts);
        BenchStats accessReq = runCase("ACCESS_REQUEST_FIRST", List.of(requestUser.getUserCode()), ts);
        BenchStats unauth = runCase("UNAUTHORIZED_UNDEBOUNCED", denyCodes, ts);
        BenchStats unknown = runCase("UNKNOWN_UNDEBOUNCED", unknownCodes, ts);

        // Concurrent 6 threads with Hikari maximum-pool-size=6: must NOT self-deadlock and must persist all 6 logs!
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (String code : concurrentCodes) {
            futures.add(pool.submit((Callable<Long>) () -> {
                String body = objectMapper.writeValueAsString(Map.of(
                        "cameraCode", camera.getCameraCode(),
                        "userCode", code,
                        "timestamp", ts
                ));
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                long t0 = System.nanoTime();
                MvcResult res = mockMvc.perform(post("/api/access-control/check-entry")
                                .header(InternalServiceKeyFilter.HEADER, serviceKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
                assertThat(res.getResponse().getStatus()).isEqualTo(200);
                return (System.nanoTime() - t0) / 1_000_000L;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Long> concurrentMs = new ArrayList<>();
        for (Future<Long> f : futures) {
            concurrentMs.add(f.get(35, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        long maxConcurrentMs = Collections.max(concurrentMs);
        Integer persistedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM camera_access_logs WHERE camera_code = ?",
                Integer.class, camera.getCameraCode());
        System.out.printf("[BENCH-CONCURRENT] maxMs=%d persistedLogs=%d%n", maxConcurrentMs, persistedLogs);

        // Assertions (red before fix, green after fix)
        assertThat(maxConcurrentMs).isLessThan(2000L);
        assertThat(persistedLogs).isEqualTo(6);
        assertThat(authUndebounced.avgSqlStmts()).isLessThanOrEqualTo(4.0);
        assertThat(authDebounced.avgSqlStmts()).isLessThanOrEqualTo(3.0);
        assertThat(authDebounced.avgTx()).isLessThanOrEqualTo(1.0);
        assertThat(assigned.avgSqlStmts()).isLessThanOrEqualTo(7.0);
        assertThat(accessReq.avgSqlStmts()).isLessThanOrEqualTo(9.0);
        assertThat(unauth.avgSqlStmts()).isLessThanOrEqualTo(9.0);
        assertThat(unknown.avgSqlStmts()).isLessThanOrEqualTo(3.0);
    }
}
