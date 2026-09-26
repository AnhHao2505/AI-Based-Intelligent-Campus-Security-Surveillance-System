package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.ReasonCatalog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AccessControlAuditLogRepository;
import com.fa26se040.icss.repository.AreaEventSessionRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.ReasonCatalogRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class Step5aSupplement2Test extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private AccessControlAuditLogRepository auditLogRepository;

    @Autowired
    private AreaEventSessionRepository sessionRepository;

    @Autowired
    private ReasonCatalogRepository reasonCatalogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AreaService areaService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private ObjectMapper objectMapper;

    private User adminUser;
    private User fmUser;
    private User guardUser;
    private User normalUser;

    private Building testBuilding;
    private Floor testFloor;
    private Area internalArea;
    private Area contactArea;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        testBuilding = buildingRepository.findByCodeIgnoreCase("BLD_SUPP2")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test Supplement 2")
                        .code("BLD_SUPP2")
                        .isActive(true)
                        .build()));

        testFloor = floorRepository.findByBuildingIdAndFloorCodeIgnoreCase(testBuilding.getId(), "F1")
                .orElseGet(() -> floorRepository.save(Floor.builder()
                        .name("Tầng 1 Test Supp2")
                        .floorCode("F1")
                        .floorOrder(1)
                        .building(testBuilding)
                        .isActive(true)
                        .build()));

        adminUser = userRepository.save(User.builder()
                .email("admin." + suffix + "@fpt.edu.vn")
                .userCode("ADM_" + suffix)
                .fullName("Admin " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        fmUser = userRepository.save(User.builder()
                .email("fm." + suffix + "@fpt.edu.vn")
                .userCode("FM_" + suffix)
                .fullName("FM " + suffix)
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        guardUser = userRepository.save(User.builder()
                .email("guard." + suffix + "@fpt.edu.vn")
                .userCode("GRD_" + suffix)
                .fullName("Guard " + suffix)
                .role(Role.GUARD)
                .accessLevel(2)
                .isActive(true)
                .build());

        normalUser = userRepository.save(User.builder()
                .email("user." + suffix + "@fpt.edu.vn")
                .userCode("USR_" + suffix)
                .fullName("Normal User " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        internalArea = areaRepository.save(Area.builder()
                .name("Khu vực Nội bộ " + suffix)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .openToMembers(false)
                .openUntil(null)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        contactArea = areaRepository.save(Area.builder()
                .name("Khu vực Liên hệ " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .openToMembers(false)
                .openUntil(null)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());
    }

    /**
     * T17: Hàm kiểm bất biến BR-EV-07:
     * đang mở (theo B1: open_to_members=true ∧ open_until!=null ∧ open_until > now)
     * ⇔ đúng 1 phiên actual_end IS NULL với planned_end = open_until > now.
     */
    private void assertEventModeInvariant(UUID areaId, OffsetDateTime now) {
        Area area = areaRepository.findById(areaId).orElseThrow();
        List<AreaEventSession> openSessions = sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(areaId) && s.getActualEnd() == null)
                .toList();

        boolean isActive = area.isEventActive(now);
        if (isActive) {
            assertEquals(1, openSessions.size(), "Bất biến BR-EV-07 vi phạm: khu vực đang mở sự kiện phải có đúng 1 phiên actual_end IS NULL");
            AreaEventSession session = openSessions.get(0);
            assertNotNull(area.getOpenUntil(), "Khu vực đang mở phải có open_until != null");
            assertEquals(area.getOpenUntil().toEpochSecond(), session.getPlannedEnd().toEpochSecond(),
                    "Bất biến BR-EV-07 vi phạm: planned_end của phiên mở phải bằng open_until của area");
            assertTrue(session.getPlannedEnd().isAfter(now), "Bất biến BR-EV-07 vi phạm: planned_end phải ở tương lai");
        } else {
            assertEquals(0, openSessions.size(), "Bất biến BR-EV-07 vi phạm: khu vực không mở sự kiện thì không được có phiên actual_end IS NULL");
        }
    }

    @Test
    @DisplayName("T9: ADMIN gọi PATCH /api/areas/{id}/event-mode -> 403 Forbidden")
    void testT9_AdminPatchEventMode_Returns403() throws Exception {
        String adminToken = jwtTokenProvider.generateToken(adminUser);
        AreaEventModeUpdateRequest req = new AreaEventModeUpdateRequest(
                true, OffsetDateTime.now().plusHours(2), "SEMINAR", "Admin thu bat su kien"
        );

        mockMvc.perform(patch("/api/areas/{id}/event-mode", internalArea.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("T10: Khu vực open_to_members = true, open_until tương lai, KHÔNG có phiên -> tắt thành công, 1 audit DISABLE; sau đó bật lại thành công")
    void testT10_NoSessionRecovery_DisableAndReenable() {
        OffsetDateTime now = OffsetDateTime.now();
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.plusHours(2));
        areaRepository.save(internalArea);

        // Đảm bảo không có phiên nào trong DB
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(internalArea.getId()))
                .toList());

        long auditBefore = auditLogRepository.count();

        // 1. Tắt sự kiện
        AreaResponse disableResp = areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat su kien khong co phien truoc do"),
                fmUser.getEmail()
        );
        assertFalse(disableResp.openToMembers());
        assertFalse(disableResp.eventActive());

        assertEquals(auditBefore + 1, auditLogRepository.count());
        AccessControlAuditLog disableLog = auditLogRepository.findAll().get((int) auditBefore);
        assertEquals(AccessControlAction.DISABLE_EVENT_MODE, disableLog.getAction());

        // 2. Bật lại sự kiện
        AreaResponse enableResp = areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat lai su kien thanh cong"),
                fmUser.getEmail()
        );
        assertTrue(enableResp.openToMembers());
        assertTrue(enableResp.eventActive());

        assertEquals(auditBefore + 2, auditLogRepository.count());
        AccessControlAuditLog enableLog = auditLogRepository.findAll().get((int) auditBefore + 1);
        assertEquals(AccessControlAction.ENABLE_EVENT_MODE, enableLog.getAction());

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T11: Sự kiện đã hết hạn -> bật với lý do EVENT_ENABLE: thành công, audit ENABLE_EVENT_MODE, phiên cũ actual_end = planned_end, ended_by NULL. Cùng tình huống, lý do EVENT_EXTEND -> 409 M1")
    void testT11_ExpiredEvent_EnableVsExtend() {
        OffsetDateTime now = OffsetDateTime.now();

        // Dựng phiên đã hết hạn nhưng chưa đóng
        AreaEventSession expiredSession = sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusHours(4))
                .plannedEnd(now.minusHours(1))
                .actualEnd(null)
                .startedBy(fmUser)
                .build());

        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.minusHours(1));
        areaRepository.save(internalArea);

        // 1. Cùng tình huống nhưng gửi lý do EVENT_EXTEND -> 409 (ERR_AREA_030)
        AreaException exExtend = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(2), "EVENT_PROLONGED", "Gia han khi su kien da het han"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, exExtend.getErrorCode());

        long auditBefore = auditLogRepository.count();

        // 2. Bật với lý do EVENT_ENABLE -> thành công
        AreaResponse enableResp = areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat lai khi phien cu da het han"),
                fmUser.getEmail()
        );
        assertTrue(enableResp.openToMembers());
        assertTrue(enableResp.eventActive());

        // Phiên cũ được dọn dẹp: actual_end = planned_end, ended_by = NULL
        AreaEventSession reloadedOld = sessionRepository.findById(expiredSession.getId()).orElseThrow();
        assertNotNull(reloadedOld.getActualEnd());
        assertEquals(reloadedOld.getPlannedEnd().toEpochSecond(), reloadedOld.getActualEnd().toEpochSecond());
        assertNull(reloadedOld.getEndedBy());

        // Audit ENABLE_EVENT_MODE được ghi
        assertEquals(auditBefore + 1, auditLogRepository.count());
        AccessControlAuditLog log = auditLogRepository.findAll().get((int) auditBefore);
        assertEquals(AccessControlAction.ENABLE_EVENT_MODE, log.getAction());

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T12: Sự kiện đã hết hạn -> tắt -> 409 M1, 0 audit mới, cờ đã được dọn (false, open_until NULL)")
    void testT12_ExpiredEvent_Disable_Returns409AndCleansFlags() {
        OffsetDateTime now = OffsetDateTime.now();

        sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusHours(4))
                .plannedEnd(now.minusHours(1))
                .actualEnd(null)
                .startedBy(fmUser)
                .build());

        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.minusHours(1));
        areaRepository.save(internalArea);

        long auditBefore = auditLogRepository.count();

        // Tắt sự kiện đã hết hạn -> 409 (ERR_AREA_030)
        AreaException ex = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat su kien da het han"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("đã kết thúc"));

        // 0 audit mới
        assertEquals(auditBefore, auditLogRepository.count());

        // Cờ đã được dọn
        Area reloaded = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertFalse(reloaded.getOpenToMembers());
        assertNull(reloaded.getOpenUntil());

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T13: Tắt khi đã tắt -> 409 M1, 0 audit mới")
    void testT13_DisableWhenAlreadyDisabled_Returns409() {
        internalArea.setOpenToMembers(false);
        internalArea.setOpenUntil(null);
        areaRepository.save(internalArea);

        long auditBefore = auditLogRepository.count();

        AreaException ex = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat khi su kien da tat san"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("đang tắt"));

        assertEquals(auditBefore, auditLogRepository.count());
        assertEventModeInvariant(internalArea.getId(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("T14: Đang mở + lý do EVENT_ENABLE -> 409 M1; đang mở + EVENT_DISABLE với enabled=true -> 409 M1")
    void testT14_MismatchedActionType_Returns409() {
        OffsetDateTime now = OffsetDateTime.now();

        // Bật sự kiện hợp lệ
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat su kien de test mismatch"),
                fmUser.getEmail()
        );

        // 1. Đang mở + lý do EVENT_ENABLE (khi cố tình đổi giờ) -> 409 (ERR_AREA_030)
        AreaException ex1 = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(4), "SEMINAR", "Gui reason EVENT_ENABLE khi dang mo"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, ex1.getErrorCode());

        // 2. Đang mở + lý do EVENT_DISABLE nhưng enabled = true -> 409 (ERR_AREA_030)
        AreaException ex2 = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(4), "ENDED_EARLY", "Gui reason EVENT_DISABLE voi enabled true"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, ex2.getErrorCode());

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T15: Đồng thời: (a) 2 lần BẬT cùng lúc -> đúng 1 thành công, 1 nhận 409; (b) TẮT + ĐIỀU CHỈNH cùng lúc -> kết quả tuần tự hợp lệ")
    void testT15_ConcurrentOperations() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // (a) Chạy 20 lần 2 lần BẬT đồng thời
            for (int i = 0; i < 20; i++) {
                OffsetDateTime now = OffsetDateTime.now();
                internalArea.setOpenToMembers(false);
                internalArea.setOpenUntil(null);
                areaRepository.save(internalArea);

                sessionRepository.deleteAll(sessionRepository.findAll().stream()
                        .filter(s -> s.getArea().getId().equals(internalArea.getId()))
                        .toList());

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger conflict409Count = new AtomicInteger(0);

                Runnable task = () -> {
                    try {
                        startLatch.await();
                        areaService.updateEventMode(
                                internalArea.getId(),
                                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat su kien dong thoi"),
                                fmUser.getEmail()
                        );
                        successCount.incrementAndGet();
                    } catch (AreaException ex) {
                        if (ex.getErrorCode() == AreaErrorCode.ERR_AREA_030) {
                            conflict409Count.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                };

                executor.submit(task);
                executor.submit(task);
                startLatch.countDown();
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

                assertEquals(1, successCount.get(), "Lần lặp " + i + ": đúng 1 request BẬT thành công");
                assertEquals(1, conflict409Count.get(), "Lần lặp " + i + ": đúng 1 request BẬT nhận 409");
                assertEventModeInvariant(internalArea.getId(), now);
            }

            // (b) Chạy 20 lần TẮT + ĐIỀU CHỈNH đồng thời
            for (int i = 0; i < 20; i++) {
                OffsetDateTime now = OffsetDateTime.now();
                // Bật sự kiện trước
                internalArea.setOpenToMembers(false);
                internalArea.setOpenUntil(null);
                areaRepository.save(internalArea);
                sessionRepository.deleteAll(sessionRepository.findAll().stream()
                        .filter(s -> s.getArea().getId().equals(internalArea.getId()))
                        .toList());

                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat ban dau cho test b"),
                        fmUser.getEmail()
                );

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);

                // Task 1: TẮT
                Runnable disableTask = () -> {
                    try {
                        startLatch.await();
                        areaService.updateEventMode(
                                internalArea.getId(),
                                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat su kien dong thoi"),
                                fmUser.getEmail()
                        );
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                };

                // Task 2: ĐIỀU CHỈNH (EXTEND)
                Runnable extendTask = () -> {
                    try {
                        startLatch.await();
                        areaService.updateEventMode(
                                internalArea.getId(),
                                new AreaEventModeUpdateRequest(true, now.plusHours(4), "EVENT_PROLONGED", "Dieu chinh gio ket thuc dong thoi"),
                                fmUser.getEmail()
                        );
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                };

                executor.submit(disableTask);
                executor.submit(extendTask);
                startLatch.countDown();
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

                assertEventModeInvariant(internalArea.getId(), now);
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("T16: ĐIỀU CHỈNH vượt ngân sách -> ERR_AREA_028; phiên hiện tại vẫn actual_end NULL, planned_end cũ (rollback)")
    void testT16_ExtendOverBudget_Rollback() {
        OffsetDateTime now = OffsetDateTime.now();
        // Dọn dẹp phiên cũ
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(contactArea.getId()))
                .toList());

        // Tạo 2 phiên quá khứ 20h mỗi phiên = 40h
        sessionRepository.save(AreaEventSession.builder()
                .area(contactArea)
                .startedAt(now.minusDays(4))
                .plannedEnd(now.minusDays(4).plusHours(20))
                .actualEnd(now.minusDays(4).plusHours(20))
                .startedBy(fmUser)
                .endedBy(fmUser)
                .build());
        sessionRepository.save(AreaEventSession.builder()
                .area(contactArea)
                .startedAt(now.minusDays(2))
                .plannedEnd(now.minusDays(2).plusHours(20))
                .actualEnd(now.minusDays(2).plusHours(20))
                .startedBy(fmUser)
                .endedBy(fmUser)
                .build());

        // Bật 4h (tổng 44h <= 48h)
        areaService.updateEventMode(
                contactArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(4), "SEMINAR", "Bat 4 gio trong ngan sach"),
                fmUser.getEmail()
        );

        AreaEventSession activeSession = sessionRepository.findByAreaIdAndActualEndIsNull(contactArea.getId()).orElseThrow();
        OffsetDateTime originalPlannedEnd = activeSession.getPlannedEnd();

        // Điều chỉnh thêm 10h (tổng sẽ là 40 + 10 = 50h > 48h) -> bị từ chối ERR_AREA_028
        AreaException ex = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        contactArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(10), "EVENT_PROLONGED", "Thu gia han vuot ngan sach"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_028, ex.getErrorCode());

        // Rollback kiểm tra: phiên hiện tại vẫn actual_end = NULL, planned_end = originalPlannedEnd
        AreaEventSession reloadedActive = sessionRepository.findById(activeSession.getId()).orElseThrow();
        assertNull(reloadedActive.getActualEnd(), "Phiên hiện tại không được bị đóng khi điều chỉnh thất bại do vượt ngân sách");
        assertEquals(originalPlannedEnd.toEpochSecond(), reloadedActive.getPlannedEnd().toEpochSecond());

        Area reloadedArea = areaRepository.findById(contactArea.getId()).orElseThrow();
        assertEquals(originalPlannedEnd.toEpochSecond(), reloadedArea.getOpenUntil().toEpochSecond());

        assertEventModeInvariant(contactArea.getId(), now);
    }

    @Test
    @DisplayName("T18: MIN_MINUTES=15: openUntil = now+5 phút -> M2. Sửa config MIN=800 khi MAX=12 -> bị từ chối. Sửa MAX=0.1 -> bị từ chối")
    void testT18_MinMinutesAndCrossConfigValidation() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. openUntil = now + 5 phút (< 15 phút) -> ERR_AREA_031 (M2)
        AreaException exMin = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusMinutes(5), "SEMINAR", "Mo 5 phut duoi muc toi thieu"),
                        fmUser.getEmail()
                )
        );
        assertEquals(AreaErrorCode.ERR_AREA_031, exMin.getErrorCode());

        // 2. Sửa MIN=800 khi MAX=12 -> bị từ chối (800 > 12 * 60 = 720)
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.EVENT_MODE_MIN_MINUTES.name(), "800", adminUser.getEmail())
        );

        // 3. Sửa MAX=0.1 (6 phút < MIN 15 phút) -> bị từ chối
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "0.1", adminUser.getEmail())
        );

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T19: BẬT / ĐIỀU CHỈNH / TẮT thành công -> mỗi GUARD đang hoạt động nhận 1 EVENT_MODE_CHANGED; bị từ chối / no-op -> 0 thông báo")
    void testT19_GuardNotificationOnEventModeChanged() {
        OffsetDateTime now = OffsetDateTime.now();
        notificationRepository.deleteAll();

        // 1. BẬT thành công
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat su kien kiem tra thong bao guard"),
                fmUser.getEmail()
        );
        List<Notification> notifs1 = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED && n.getRecipient().getId().equals(guardUser.getId()))
                .toList();
        assertEquals(1, notifs1.size());
        assertTrue(notifs1.get(0).getMessage().contains("Bật chế độ sự kiện"));

        // 2. ĐIỀU CHỈNH no-op (< 1s lệch giờ) -> 0 thông báo mới
        OffsetDateTime exactSameTime = areaRepository.findById(internalArea.getId()).orElseThrow().getOpenUntil();
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, exactSameTime, "EVENT_PROLONGED", "Dieu chinh no op khong doi gio"),
                fmUser.getEmail()
        );
        List<Notification> notifsNoOp = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED && n.getRecipient().getId().equals(guardUser.getId()))
                .toList();
        assertEquals(1, notifsNoOp.size(), "No-op không được gửi thông báo mới");

        // 3. ĐIỀU CHỈNH thành công
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(4), "EVENT_PROLONGED", "Dieu chinh gio ket thuc hop le"),
                fmUser.getEmail()
        );
        List<Notification> notifs2 = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED && n.getRecipient().getId().equals(guardUser.getId()))
                .toList();
        assertEquals(2, notifs2.size());
        assertTrue(notifs2.get(1).getMessage().contains("Điều chỉnh giờ kết thúc"));

        // 4. TẮT thành công
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat su kien hop le theo lich"),
                fmUser.getEmail()
        );
        List<Notification> notifs3 = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED && n.getRecipient().getId().equals(guardUser.getId()))
                .toList();
        assertEquals(3, notifs3.size());
        assertTrue(notifs3.get(2).getMessage().contains("Tắt chế độ sự kiện"));

        // 5. Thao tác bị từ chối (Tắt khi đã tắt) -> 0 thông báo mới
        assertThrows(AreaException.class, () ->
                areaService.updateEventMode(
                        internalArea.getId(),
                        new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Thu tat khi da tat"),
                        fmUser.getEmail()
                )
        );
        List<Notification> notifsRejected = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED && n.getRecipient().getId().equals(guardUser.getId()))
                .toList();
        assertEquals(3, notifsRejected.size(), "Thao tác bị từ chối không sinh thông báo");

        assertEventModeInvariant(internalArea.getId(), now);
    }

    @Test
    @DisplayName("T20: Job nhắc: phiên còn 20 phút, REMINDER=30 -> started_by nhận 1 EVENT_MODE_EXPIRING; chạy job lần 2 -> vẫn 1; REMINDER=0 -> không nhắc; started_by bị khoá -> mọi FM nhận")
    void testT20_ExpiryReminderJob() {
        OffsetDateTime now = OffsetDateTime.now();
        notificationRepository.deleteAll();

        // 1. Tạo phiên còn 20 phút, REMINDER=30
        AreaEventSession session = sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusHours(2))
                .plannedEnd(now.plusMinutes(20))
                .actualEnd(null)
                .startedBy(fmUser)
                .expiryRemindedAt(null)
                .build());
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.plusMinutes(20));
        areaRepository.save(internalArea);

        areaService.scanAndSendEventModeExpiryReminders();

        List<Notification> expiringNotifs = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_EXPIRING && n.getRecipient().getId().equals(fmUser.getId()))
                .toList();
        assertEquals(1, expiringNotifs.size());

        AreaEventSession reloadedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertNotNull(reloadedSession.getExpiryRemindedAt());

        // Chạy lần 2 -> không tạo thêm thông báo
        areaService.scanAndSendEventModeExpiryReminders();
        List<Notification> expiringNotifs2 = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_EXPIRING && n.getRecipient().getId().equals(fmUser.getId()))
                .toList();
        assertEquals(1, expiringNotifs2.size());

        // 2. REMINDER = 0 -> không nhắc
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "0", adminUser.getEmail());
        AreaEventSession session2 = sessionRepository.save(AreaEventSession.builder()
                .area(contactArea)
                .startedAt(now.minusHours(2))
                .plannedEnd(now.plusMinutes(20))
                .actualEnd(null)
                .startedBy(fmUser)
                .expiryRemindedAt(null)
                .build());
        contactArea.setOpenToMembers(true);
        contactArea.setOpenUntil(now.plusMinutes(20));
        areaRepository.save(contactArea);

        areaService.scanAndSendEventModeExpiryReminders();
        AreaEventSession reloaded2 = sessionRepository.findById(session2.getId()).orElseThrow();
        assertNull(reloaded2.getExpiryRemindedAt());

        // Khôi phục REMINDER = 30
        systemConfigService.update(ConfigKey.EVENT_MODE_EXPIRY_REMINDER_MINUTES.name(), "30", adminUser.getEmail());

        // 3. started_by bị vô hiệu hoá -> mọi FM đang hoạt động nhận
        fmUser.setIsActive(false);
        userRepository.save(fmUser);

        User activeFM2 = userRepository.save(User.builder()
                .email("fm2." + UUID.randomUUID() + "@fpt.edu.vn")
                .userCode("FM2_" + UUID.randomUUID().toString().substring(0, 5))
                .fullName("Active FM 2")
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        areaService.scanAndSendEventModeExpiryReminders();

        List<Notification> fm2Notifs = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_EXPIRING && n.getRecipient().getId().equals(activeFM2.getId()))
                .toList();
        assertFalse(fm2Notifs.isEmpty());

        // Khôi phục fmUser
        fmUser.setIsActive(true);
        userRepository.save(fmUser);
    }

    @Test
    @DisplayName("T21: Hạ BUDGET_HOURS khi có khu vực đang mở vượt ngân sách mới -> mọi FM nhận 1 EVENT_MODE_LIMIT_CHANGED; hạ khi không vi phạm -> 0")
    void testT21_BudgetReductionViolatingLimitNotification() {
        OffsetDateTime now = OffsetDateTime.now();
        notificationRepository.deleteAll();

        // Dọn các area đang mở khác
        List<Area> others = areaRepository.findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(now);
        for (Area a : others) {
            a.setOpenToMembers(false);
            a.setOpenUntil(null);
            areaRepository.save(a);
        }

        // Tạo chuỗi phiên 40h cho internalArea, đang mở thêm 4h nữa
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(internalArea.getId()))
                .toList());

        sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusDays(4))
                .plannedEnd(now.minusDays(4).plusHours(20))
                .actualEnd(now.minusDays(4).plusHours(20))
                .startedBy(fmUser)
                .endedBy(fmUser)
                .build());
        sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusDays(2))
                .plannedEnd(now.minusDays(2).plusHours(20))
                .actualEnd(now.minusDays(2).plusHours(20))
                .startedBy(fmUser)
                .endedBy(fmUser)
                .build());

        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(4), "SEMINAR", "Bat them 4 gio tong 44 gio"),
                fmUser.getEmail()
        );

        long notifsBefore = notificationRepository.count();

        // Hạ BUDGET_HOURS còn 30 (44h > 30h) -> FM nhận thông báo
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "30", adminUser.getEmail());

        List<Notification> limitNotifs = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_LIMIT_CHANGED)
                .toList();
        assertTrue(limitNotifs.size() > notifsBefore);
        assertTrue(limitNotifs.stream().anyMatch(n -> n.getMessage().contains(internalArea.getName())));

        // Khôi phục BUDGET_HOURS = 48
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", adminUser.getEmail());

        // Tắt sự kiện internalArea
        areaService.updateEventMode(
                internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat de khong con vi pham"),
                fmUser.getEmail()
        );

        long countAfterDisable = notificationRepository.count();

        // Hạ BUDGET_HOURS khi không có khu vực nào vi phạm -> 0 thông báo
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "30", adminUser.getEmail());
        assertEquals(countAfterDisable, notificationRepository.count());

        // Khôi phục lại
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", adminUser.getEmail());
    }

    @Test
    @DisplayName("T22: checkEntry với sự kiện hết hạn (cờ open_to_members vẫn true) -> không trả OPEN_EVENT")
    void testT22_CheckEntryWithExpiredEvent_DoesNotReturnOpenEvent() {
        OffsetDateTime now = OffsetDateTime.now();

        // Khu vực internalArea có level = 2. normalUser có accessLevel = 1.
        // Đặt open_to_members = true nhưng open_until trong quá khứ (đã hết hạn)
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.minusMinutes(10));
        areaRepository.save(internalArea);

        AccessDecision decision = accessDecisionService.checkEntry(
                normalUser.getId(),
                internalArea.getId(),
                now
        );

        // Quyết định không được là OPEN_EVENT và bị từ chối do level không đủ (level 1 < level 2)
        assertNotEquals(AccessSource.OPEN_EVENT, decision.source());
        assertFalse(decision.allowed());
    }
}
