package com.fa26se040.icss.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaEventScheduleRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Việc 9: khu mở theo lịch thì luôn có phiên sự kiện. check-entry cho vào ở bước 4c (lịch SCHEDULED phủ thời điểm, chưa
 * có phiên) -> AccessDecisionService kích hoạt lịch nền bằng AreaService.activateScheduleInTx (hàm của job), sau khi
 * quyết định đã trả về; chống trùng nhờ khoá dòng khu + kiểm trạng thái lịch trong activateScheduleInTx.
 * DB test thật, transaction thật, luồng nền thật (eventActivationExecutor).
 *
 * <p>activateScheduleInTx đánh FAILED nếu lịch chạm khung ban đêm hiện hành; lịch dựng theo giờ hiện tại nên tắt khung ban
 * đêm trong lúc test (AfterHoursTestSupport, cùng cách AreaEventScheduleIntegrationTest), trả lại ở @AfterEach.</p>
 */
class EventScheduleEntryActivationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AccessDecisionService accessDecisionService;
    @Autowired private AreaService areaService;
    @Autowired private AreaEventScheduleRepository scheduleRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private SystemConfigurationRepository systemConfigurationRepository;

    private User student;
    private User fm;
    private User admin;
    private String[] nightWindowBefore;
    private Area area;
    private Object decisionTarget;
    private Object realProvider;
    private ListAppender<ILoggingEvent> logs;
    private Logger decisionLogger;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa nhà Test Lượt vào kích hoạt lịch")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa nhà Test Lượt vào kích hoạt lịch")
                        .isActive(true).build()));
        Floor floor = floorRepository.findByBuildingIdAndNameIgnoreCase(building.getId(), "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().name("Tầng 1").floorOrder(1).building(building)
                        .isActive(true).build()));
        student = userRepository.save(User.builder().email("sv.evact." + suffix + "@fpt.edu.vn").userCode("SV_EA_" + suffix)
                .fullName("SV kích hoạt lịch " + suffix).role(Role.NORMAL_USER).accessLevel(1).isActive(true).build());
        fm = userRepository.save(User.builder().email("fm.evact." + suffix + "@fpt.edu.vn").userCode("FM_EA_" + suffix)
                .fullName("FM kích hoạt lịch " + suffix).role(Role.FACILITY_MANAGER).accessLevel(3).isActive(true).build());
        admin = userRepository.save(User.builder().email("adm.evact." + suffix + "@fpt.edu.vn").userCode("ADM_EA_" + suffix)
                .fullName("Admin kích hoạt lịch " + suffix).role(Role.ADMIN).accessLevel(3).isActive(true).build());
        nightWindowBefore = AfterHoursTestSupport.disableNightWindow(systemConfigService, systemConfigurationRepository);
        area = areaRepository.save(Area.builder().name("Khu kích hoạt lịch " + suffix).areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2).floorEntity(floor).building(building.getName()).floor(floor.getName())
                .isActive(true).openToMembers(false).build());

        decisionTarget = AopTestUtils.getUltimateTargetObject(accessDecisionService);
        realProvider = ReflectionTestUtils.getField(decisionTarget, "areaServiceProvider");
        decisionLogger = (Logger) LoggerFactory.getLogger(AccessDecisionService.class);
        logs = new ListAppender<>();
        logs.start();
        decisionLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(decisionTarget, "areaServiceProvider", realProvider);
        decisionLogger.detachAppender(logs);
        AfterHoursTestSupport.restore(systemConfigService, systemConfigurationRepository, nightWindowBefore, admin.getEmail());
        nightWindowBefore = null;
    }

    private AreaEventSchedule dueSchedule() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return scheduleRepository.save(AreaEventSchedule.builder()
                .title("Sự kiện mở theo lịch")
                .area(area)
                .startAt(now.minusMinutes(5))
                .endAt(now.plusHours(1))
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode("EVT_TEST_ENABLE")
                .reasonLabel("Hội thảo lên lịch")
                .note("Lịch cho test việc 9")
                .createdBy(fm)
                .build());
    }

    private int sessionsOf(UUID scheduleId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM area_event_sessions WHERE schedule_id = ?", Integer.class, scheduleId);
        return n == null ? 0 : n;
    }

    private int systemEnableAudits() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id = ? AND action = 'ENABLE_EVENT_MODE'"
                + " AND actor_type = 'SYSTEM' AND actor_source = 'EVENT_SCHEDULE_ACTIVATION'", Integer.class, area.getId().toString());
        return n == null ? 0 : n;
    }

    private String scheduleStatus(UUID scheduleId) {
        return jdbc.queryForObject("SELECT status FROM area_event_schedules WHERE id = ?", String.class, scheduleId);
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private void assertScheduleEntry(AccessDecision decision, UUID scheduleId) {
        assertThat(decision.allowed()).as(decision.reason()).isTrue();
        assertThat(decision.source()).isEqualTo(AccessSource.OPEN_EVENT);
        assertThat(decision.reason()).isEqualTo("Khu vực trong khung lịch sự kiện");
    }

    @Test
    @DisplayName("EA-01: lịch tới giờ, chưa có phiên, có lượt vào -> cho vào (4c) và nền tạo đúng 1 phiên, lịch STARTED, 1 audit SYSTEM; log ghi do lượt vào")
    void entryActivatesScheduleOnce() throws Exception {
        AreaEventSchedule schedule = dueSchedule();

        AccessDecision decision = accessDecisionService.checkEntry(student.getId(), area.getId(), OffsetDateTime.now());

        assertScheduleEntry(decision, schedule.getId());
        assertThat(waitFor(() -> "STARTED".equals(scheduleStatus(schedule.getId())), 5000))
                .as("lịch phải được kích hoạt nền (hiện: %s)", scheduleStatus(schedule.getId())).isTrue();
        assertThat(sessionsOf(schedule.getId())).isEqualTo(1);
        assertThat(systemEnableAudits()).isEqualTo(1);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("do lượt vào (check-entry), không phải do job")
                && e.getFormattedMessage().contains(schedule.getId().toString()));

        // lượt sau: đã có phiên -> cho vào ở bước 4b (phiên), vẫn OPEN_EVENT
        AccessDecision next = accessDecisionService.checkEntry(student.getId(), area.getId(), OffsetDateTime.now());
        assertThat(next.allowed()).isTrue();
        assertThat(next.reason()).isEqualTo("Khu vực đang mở chế độ sự kiện cho thành viên");
    }

    @Test
    @DisplayName("EA-02: 2 lượt vào đồng thời -> cả 2 được cho vào, vẫn chỉ 1 phiên và 1 audit")
    void concurrentEntries_singleSession() throws Exception {
        AreaEventSchedule schedule = dueSchedule();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<AccessDecision>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return accessDecisionService.checkEntry(student.getId(), area.getId(), OffsetDateTime.now());
                }));
            }
            go.countDown();
            for (Future<AccessDecision> f : results) {
                assertScheduleEntry(f.get(10, TimeUnit.SECONDS), schedule.getId());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(waitFor(() -> "STARTED".equals(scheduleStatus(schedule.getId())), 5000)).isTrue();
        Thread.sleep(500); // cho tác vụ nền thứ hai (nếu có) chạy xong
        assertThat(sessionsOf(schedule.getId())).isEqualTo(1);
        assertThat(systemEnableAudits()).isEqualTo(1);
    }

    @Test
    @DisplayName("EA-03: hai lần activateScheduleInTx chạy song song (vd. lượt vào và job cùng lúc) -> khoá khu, chỉ 1 phiên")
    void concurrentActivations_singleSession() throws Exception {
        AreaEventSchedule schedule = dueSchedule();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    areaService.activateScheduleInTx(schedule.getId(), OffsetDateTime.now());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : runs) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(scheduleStatus(schedule.getId())).isEqualTo("STARTED");
        assertThat(sessionsOf(schedule.getId())).isEqualTo(1);
        assertThat(systemEnableAudits()).isEqualTo(1);
    }

    @Test
    @DisplayName("EA-04: job chạy sau khi lượt vào đã kích hoạt -> không tạo phiên thứ hai")
    void jobAfterEntry_noSecondSession() throws Exception {
        AreaEventSchedule schedule = dueSchedule();
        accessDecisionService.checkEntry(student.getId(), area.getId(), OffsetDateTime.now());
        assertThat(waitFor(() -> "STARTED".equals(scheduleStatus(schedule.getId())), 5000)).isTrue();

        areaService.processScheduledEventActivations(OffsetDateTime.now());

        assertThat(sessionsOf(schedule.getId())).isEqualTo(1);
        assertThat(systemEnableAudits()).isEqualTo(1);
    }

    @Test
    @DisplayName("EA-05: kích hoạt lỗi -> quyết định vẫn là cho vào (OPEN_EVENT), lỗi chỉ log ERROR, không có phiên")
    void activationFails_decisionStillAllowed() throws Exception {
        AreaEventSchedule schedule = dueSchedule();
        AreaService failing = mock(AreaService.class);
        doThrow(new IllegalStateException("giả lập lỗi kích hoạt")).when(failing).activateScheduleInTx(any(), any());
        @SuppressWarnings("unchecked")
        ObjectProvider<AreaService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(failing);
        ReflectionTestUtils.setField(decisionTarget, "areaServiceProvider", provider);

        AccessDecision decision = accessDecisionService.checkEntry(student.getId(), area.getId(), OffsetDateTime.now());

        assertScheduleEntry(decision, schedule.getId());
        assertThat(waitFor(() -> logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("do lượt vào thất bại")
                && e.getFormattedMessage().contains(schedule.getId().toString())), 5000))
                .as("lỗi kích hoạt phải được ghi log ERROR").isTrue();
        assertThat(sessionsOf(schedule.getId())).isZero();
        assertThat(scheduleStatus(schedule.getId())).isEqualTo("SCHEDULED");
    }
}
