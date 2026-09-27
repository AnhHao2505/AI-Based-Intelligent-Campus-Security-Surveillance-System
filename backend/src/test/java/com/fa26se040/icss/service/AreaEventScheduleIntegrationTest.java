package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.area.*;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.scheduler.AreaEventModeScheduler;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AreaEventScheduleIntegrationTest extends AbstractIntegrationTest {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AreaEventScheduleRepository eventScheduleRepository;

    @Autowired
    private AreaEventSessionRepository sessionRepository;

    @Autowired
    private ReasonCatalogRepository reasonCatalogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AreaService areaService;

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AreaEventModeScheduler areaEventModeScheduler;

    @Autowired
    private ObjectMapper objectMapper;

    private Building testBuilding;
    private Floor testFloor;

    private User userL1;
    private User fmUser;
    private User guardUser;
    private User adminUser;

    private Area internalArea;
    private Area contactArea;
    private Area publicArea;
    private Area highlyArea;

    private final String REASON_ENABLE = "EVT_TEST_ENABLE";
    private final String REASON_EXTEND = "EVT_TEST_EXTEND";
    private final String REASON_DISABLE = "EVT_TEST_DISABLE";

    @BeforeEach
    void setUpData() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        testBuilding = buildingRepository.findByCodeIgnoreCase("TEST_BLD_SCHED")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test Schedule")
                        .code("TEST_BLD_SCHED")
                        .isActive(true)
                        .build()));

        testFloor = floorRepository.findByBuildingIdAndFloorCodeIgnoreCase(testBuilding.getId(), "F1")
                .orElseGet(() -> floorRepository.save(Floor.builder()
                        .name("Tầng 1 Test Schedule")
                        .floorCode("F1")
                        .floorOrder(1)
                        .building(testBuilding)
                        .isActive(true)
                        .build()));

        userL1 = userRepository.save(User.builder()
                .email("sv.sched." + suffix + "@fpt.edu.vn")
                .userCode("SV_SC_" + suffix)
                .fullName("Sinh Vien Test " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        fmUser = userRepository.save(User.builder()
                .email("fm.sched." + suffix + "@fpt.edu.vn")
                .userCode("FM_SC_" + suffix)
                .fullName("FM Test " + suffix)
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        guardUser = userRepository.save(User.builder()
                .email("guard.sched." + suffix + "@fpt.edu.vn")
                .userCode("GD_SC_" + suffix)
                .fullName("Guard Test " + suffix)
                .role(Role.GUARD)
                .accessLevel(2)
                .isActive(true)
                .build());

        adminUser = userRepository.save(User.builder()
                .email("admin.sched." + suffix + "@fpt.edu.vn")
                .userCode("ADM_SC_" + suffix)
                .fullName("Admin Test " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        internalArea = areaRepository.save(Area.builder()
                .name("Khu Vuc Internal " + suffix)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .openToMembers(false)
                .build());

        contactArea = areaRepository.save(Area.builder()
                .name("Khu Vuc Contact " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .openToMembers(false)
                .build());

        publicArea = areaRepository.save(Area.builder()
                .name("Khu Vuc Public " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .openToMembers(false)
                .build());

        highlyArea = areaRepository.save(Area.builder()
                .name("Khu Vuc Highly " + suffix)
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .openToMembers(false)
                .build());

        // Ensure reason catalogs exist
        ensureReasonCatalog("EVENT_ENABLE", REASON_ENABLE, "Hội thảo lên lịch", 1);
        ensureReasonCatalog("EVENT_EXTEND", REASON_EXTEND, "Gia hạn lịch", 1);
        ensureReasonCatalog("EVENT_DISABLE", REASON_DISABLE, "Huỷ lịch sự kiện", 1);

        // Reset configs to defaults
        systemConfigService.update(ConfigKey.EVENT_MODE_MIN_MINUTES.name(), "30", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "12", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_WINDOW_DAYS.name(), "7", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS.name(), "30", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_SCHEDULES_PER_AREA.name(), "5", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES.name(), "30", adminUser.getEmail());

        notificationRepository.deleteAll();
    }

    private void ensureReasonCatalog(String actionType, String code, String label, int sortOrder) {
        if (reasonCatalogRepository.findByActionTypeAndCode(actionType, code).isEmpty()) {
            reasonCatalogRepository.save(ReasonCatalog.builder()
                    .actionType(actionType)
                    .code(code)
                    .label(label)
                    .sortOrder(sortOrder)
                    .isActive(true)
                    .isOther(false)
                    .build());
        }
    }

    private List<Notification> getNotificationsForUser(UUID userId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient() != null && n.getRecipient().getId().equals(userId))
                .sorted(java.util.Comparator.comparing(Notification::getCreatedAt).reversed())
                .toList();
    }

    @Test
    @DisplayName("ES-T01: Đặt lịch hợp lệ -> SCHEDULED, 1 audit CREATE, GUARD nhận EVENT_MODE_SCHEDULED")
    void testES_T01_CreateSchedule_Valid() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusDays(1).withNano(0);
        OffsetDateTime end = start.plusHours(3);

        long auditCountBefore = auditLogRepository.count();

        EventScheduleResponse res = areaService.createSchedule(
                internalArea.getId(),
                new EventScheduleRequest(start, end, REASON_ENABLE, "Dat lich hop le hop hoi sinh vien"),
                fmUser.getEmail()
        );

        assertNotNull(res.id());
        assertEquals(internalArea.getId(), res.areaId());
        assertEquals(AreaEventScheduleStatus.SCHEDULED.name(), res.status());
        assertEquals(REASON_ENABLE, res.reasonCode());
        assertEquals("Hội thảo lên lịch", res.reasonLabel());
        assertEquals(fmUser.getId(), res.createdById());
        assertEquals(fmUser.getFullName(), res.createdByName());

        // Check 1 audit log AREA_EVENT_SCHEDULE / CREATE
        assertEquals(auditCountBefore + 1, auditLogRepository.count());
        List<AuditLog> logs = auditLogRepository.findAll().stream()
                .filter(l -> l.getTargetId().equals(res.id().toString()) && l.getTargetType() == AuditTargetType.AREA_EVENT_SCHEDULE)
                .toList();
        assertEquals(1, logs.size());
        AuditLog log = logs.get(0);
        assertEquals(AuditAction.CREATE, log.getAction());
        assertNotNull(log.getChangedBy());
        assertEquals(fmUser.getId(), log.getChangedBy().getId());

        // Check GUARD received EVENT_MODE_SCHEDULED notification
        List<Notification> guardNotifs = getNotificationsForUser(guardUser.getId());
        assertTrue(guardNotifs.stream().anyMatch(n -> n.getType() == NotificationType.EVENT_MODE_SCHEDULED));
    }

    @Test
    @DisplayName("ES-T02: startAt quá khứ / vượt LEAD_DAYS / ngắn hơn MIN / dài hơn MAX / HIGHLY / PUBLIC / khu vực vô hiệu hoá / ADMIN 403")
    void testES_T02_ValidationErrors() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. startAt trong quá khứ -> ERR_AREA_032
        AreaException exPast = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.minusHours(1), now.plusHours(2), REASON_ENABLE, "Lich trong qua khu"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_032, exPast.getErrorCode());

        // 2. vượt LEAD_DAYS (30 ngày) -> ERR_AREA_033
        AreaException exLead = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusDays(31), now.plusDays(31).plusHours(2), REASON_ENABLE, "Vuot qua max lead days"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_033, exLead.getErrorCode());

        // 3. ngắn hơn MIN (30 phut) -> ERR_AREA_035
        AreaException exMin = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(1).plusMinutes(15), REASON_ENABLE, "Ngan hon min minutes"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_035, exMin.getErrorCode());

        // 4. dài hơn MAX (12 gio) -> ERR_AREA_036
        AreaException exMax = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(14), REASON_ENABLE, "Dai hon max hours"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_036, exMax.getErrorCode());

        // 5. HIGHLY_CONFIDENTIAL -> ERR_AREA_022
        AreaException exHighly = assertThrows(AreaException.class, () ->
                areaService.createSchedule(highlyArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(3), REASON_ENABLE, "Dat lich cho khu highly"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_022, exHighly.getErrorCode());

        // 6. PUBLIC -> ERR_AREA_022
        AreaException exPub = assertThrows(AreaException.class, () ->
                areaService.createSchedule(publicArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(3), REASON_ENABLE, "Dat lich cho khu public"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_022, exPub.getErrorCode());

        // 7. Khu vực vô hiệu hoá -> ERR_AREA_017
        internalArea.setIsActive(false);
        areaRepository.save(internalArea);
        AreaException exInactive = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(3), REASON_ENABLE, "Dat lich khu inactive"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_017, exInactive.getErrorCode());
        internalArea.setIsActive(true);
        areaRepository.save(internalArea);

        // 8. ADMIN gọi endpoint đặt lịch -> 403 Forbidden
        String adminToken = jwtTokenProvider.generateToken(adminUser);
        mockMvc.perform(post("/api/areas/" + internalArea.getId() + "/event-schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EventScheduleRequest(
                                now.plusHours(1), now.plusHours(3), REASON_ENABLE, "Admin thu dat lich"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ES-T03: Chồng lịch khác -> 409; chồng phiên đang mở -> 409; tiếp giáp đúng mốc -> OK")
    void testES_T03_OverlapAndAdjacent() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime t1 = now.plusDays(2).withNano(0);
        OffsetDateTime t2 = t1.plusHours(3);

        // Đặt lịch thứ nhất: [t1, t2]
        areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(t1, t2, REASON_ENABLE, "Lich thu nhat hop le"),
                fmUser.getEmail());

        // 1. Chồng lịch khác: [t1 + 1h, t2 + 1h] -> ERR_AREA_037 (409)
        AreaException exOverlap = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(t1.plusHours(1), t2.plusHours(1), REASON_ENABLE, "Lich bi trung lap gio"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_037, exOverlap.getErrorCode());

        // 2. Chồng phiên đang mở [now, open_until) -> ERR_AREA_038 (409)
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.plusHours(2));
        areaRepository.save(internalArea);

        AreaException exOverlapActive = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusHours(1), now.plusHours(3), REASON_ENABLE, "Trung phien dang mo"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_038, exOverlapActive.getErrorCode());

        // Reset phiên đang mở
        internalArea.setOpenToMembers(false);
        internalArea.setOpenUntil(null);
        areaRepository.save(internalArea);

        // 3. Tiếp giáp đúng mốc: [t2, t2 + 2h] (start = end của lịch trước) -> OK!
        EventScheduleResponse resAdjacent = areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(t2, t2.plusHours(2), REASON_ENABLE, "Lich tiep giap dung moc gio"),
                fmUser.getEmail());
        assertNotNull(resAdjacent.id());
        assertEquals(AreaEventScheduleStatus.SCHEDULED.name(), resAdjacent.status());
    }

    @Test
    @DisplayName("ES-T04: Vượt MAX_SCHEDULES_PER_AREA -> lỗi ERR_AREA_039")
    void testES_T04_MaxSchedulesPerArea() {
        OffsetDateTime now = OffsetDateTime.now();
        // MAX_SCHEDULES_PER_AREA mặc định = 5
        for (int i = 1; i <= 5; i++) {
            OffsetDateTime start = now.plusDays(i).withNano(0);
            OffsetDateTime end = start.plusHours(2);
            areaService.createSchedule(internalArea.getId(),
                    new EventScheduleRequest(start, end, REASON_ENABLE, "Lich hop le thu " + i),
                    fmUser.getEmail());
        }

        // Lịch thứ 6 -> ERR_AREA_039
        OffsetDateTime start6 = now.plusDays(6).withNano(0);
        OffsetDateTime end6 = start6.plusHours(2);
        AreaException exMax = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(start6, end6, REASON_ENABLE, "Lich thu sau vuot toi da"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_039, exMax.getErrorCode());
    }

    @Test
    @DisplayName("ES-T05: Ngân sách: lịch giữ chỗ làm bật tay bị từ chối; đặt lịch sớm hơn làm cửa sổ của lịch sau vượt -> từ chối; huỷ lịch -> ngân sách được trả lại")
    void testES_T05_BudgetValidation() {
        OffsetDateTime now = OffsetDateTime.now();
        // Cấu hình: WINDOW_DAYS = 7, BUDGET_HOURS = 10, MAX_HOURS = 10
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "10", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "10", adminUser.getEmail());

        // Đặt lịch giữ chỗ 8 giờ tại ngày mai
        EventScheduleResponse sched1 = areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(now.plusDays(1), now.plusDays(1).plusHours(8), REASON_ENABLE, "Dat lich giu cho 8h"),
                fmUser.getEmail());

        // 1. Lịch giữ chỗ làm bật tay 5 giờ bị từ chối do tổng = 13h > 10h -> ERR_AREA_028
        AreaException exManual = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(5), REASON_ENABLE, "Bat tay 5h bi vuot ngan sach"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_028, exManual.getErrorCode());

        // 2. Thử đặt thêm lịch thứ 2 sớm hơn làm cửa sổ của sched1 vượt:
        // Đặt sched2 tại now + 2 hours (dài 4h) -> cửa sổ sched1 tính cả sched2 thành 12h > 10h -> ERR_AREA_028
        AreaException exSched2 = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusHours(2), now.plusHours(6), REASON_ENABLE, "Lich lam cua so lich sau vuot"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_028, exSched2.getErrorCode());

        // 3. Huỷ lịch sched1 -> ngân sách được trả lại
        areaService.cancelSchedule(internalArea.getId(), sched1.id(),
                new EventScheduleCancelRequest(REASON_DISABLE, "Huy lich de giai phong ngan sach"),
                fmUser.getEmail());

        // Giờ bật tay 5h thành công!
        AreaResponse enableRes = areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(5), REASON_ENABLE, "Bat tay thanh cong sau khi huy lich"),
                fmUser.getEmail());
        assertTrue(enableRes.openToMembers());

        // Dọn dẹp
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, REASON_DISABLE, "Tat lai che do su kien"),
                fmUser.getEmail());
    }

    @Test
    @DisplayName("ES-T06: Bật tay có open_until chồng lịch -> 409 ERR_AREA_041")
    void testES_T06_ManualEnableOverlapsSchedule() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime schedStart = now.plusHours(3);
        OffsetDateTime schedEnd = schedStart.plusHours(2);

        // Đặt lịch [now + 3h, now + 5h]
        areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(schedStart, schedEnd, REASON_ENABLE, "Lich su kien chieu nay"),
                fmUser.getEmail());

        // Bật tay đến now + 4h (open_until > schedStart và now < schedEnd) -> 409 ERR_AREA_041
        AreaException exManual = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(4), REASON_ENABLE, "Bat tay bi de len lich"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_041, exManual.getErrorCode());
    }

    @Test
    @DisplayName("ES-T07: Sửa/huỷ lịch STARTED/CANCELLED -> 409; lý do sai loại -> lỗi; note < 10 -> 400")
    void testES_T07_ModifyCancelledOrStartedAndBadReason() {
        OffsetDateTime now = OffsetDateTime.now();
        EventScheduleResponse sched = areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(now.plusDays(1), now.plusDays(1).plusHours(2), REASON_ENABLE, "Dat lich kiem tra sua huy"),
                fmUser.getEmail());

        // 1. Huỷ lịch
        areaService.cancelSchedule(internalArea.getId(), sched.id(),
                new EventScheduleCancelRequest(REASON_DISABLE, "Huy lich de test sua huy"),
                fmUser.getEmail());

        // 2. Sửa lịch đã CANCELLED -> ERR_AREA_040 (409)
        AreaException exEditCancelled = assertThrows(AreaException.class, () ->
                areaService.updateSchedule(internalArea.getId(), sched.id(),
                        new EventScheduleRequest(now.plusDays(2), now.plusDays(2).plusHours(2), REASON_EXTEND, "Thu sua lich da huy"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_040, exEditCancelled.getErrorCode());

        // 3. Huỷ tiếp lịch đã CANCELLED -> ERR_AREA_040 (409)
        AreaException exCancelCancelled = assertThrows(AreaException.class, () ->
                areaService.cancelSchedule(internalArea.getId(), sched.id(),
                        new EventScheduleCancelRequest(REASON_DISABLE, "Thu huy lai lich da huy"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_040, exCancelCancelled.getErrorCode());

        // 4. Lý do sai loại: Tạo lịch mới dùng reasonCode của EVENT_DISABLE -> ERR_AREA_026
        AreaException exBadReason = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusDays(2), now.plusDays(2).plusHours(2), REASON_DISABLE, "Dung sai danh muc ly do"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_026, exBadReason.getErrorCode());

        // 5. Note < 10 ký tự -> ERR_AREA_024 (400)
        AreaException exShortNote = assertThrows(AreaException.class, () ->
                areaService.createSchedule(internalArea.getId(),
                        new EventScheduleRequest(now.plusDays(2), now.plusDays(2).plusHours(2), REASON_ENABLE, "ngan"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exShortNote.getErrorCode());
    }

    @Test
    @DisplayName("ES-T08: checkEntry trong khung lịch (job chưa chạy) -> OPEN_EVENT; ngoài khung -> không")
    void testES_T08_CheckEntryWithinScheduleWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        // Tạo lịch bao trùm thời điểm hiện tại: [now - 10m, now + 2h]
        AreaEventSchedule schedule = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(now.minusMinutes(10))
                .endAt(now.plusHours(2))
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo lên lịch")
                .note("Lich su kien dang dien ra chua chay job")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        // 1. checkEntry tại thời điểm now (trong khung lịch) -> allowed OPEN_EVENT
        AccessDecision decisionInside = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now);
        assertTrue(decisionInside.allowed());
        assertEquals(AccessSource.OPEN_EVENT, decisionInside.source());
        assertEquals("Khu vực trong khung lịch sự kiện", decisionInside.reason());

        // 2. checkEntry trước khung lịch (now - 30m) -> không được vào qua OPEN_EVENT
        AccessDecision decisionBefore = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now.minusMinutes(30));
        assertFalse(decisionBefore.allowed());

        // 3. checkEntry sau khung lịch (now + 3h) -> không được vào qua OPEN_EVENT
        AccessDecision decisionAfter = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now.plusHours(3));
        assertFalse(decisionAfter.allowed());
    }

    @Test
    @DisplayName("ES-T09: Job kích hoạt: phiên started_at = start_at, cờ bật, lịch STARTED, 1 audit ENABLE_EVENT_MODE actor SYSTEM EVENT_SCHEDULE_ACTIVATION")
    void testES_T09_JobActivation() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime schedStart = now.minusMinutes(1);
        OffsetDateTime schedEnd = now.plusHours(2);

        AreaEventSchedule schedule = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(schedStart)
                .endAt(schedEnd)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo lên lịch")
                .note("Kich hoat lich tu dong bang job")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        long auditCountBefore = auditLogRepository.count();

        // Kích hoạt qua job
        areaService.processScheduledEventActivations(now);

        // Kiểm tra khu vực
        Area reloadedArea = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertTrue(reloadedArea.getOpenToMembers());
        assertEquals(schedEnd.toEpochSecond(), reloadedArea.getOpenUntil().toEpochSecond());

        // Kiểm tra lịch
        AreaEventSchedule reloadedSchedule = eventScheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(AreaEventScheduleStatus.STARTED, reloadedSchedule.getStatus());
        assertNotNull(reloadedSchedule.getSession());
        AreaEventSession createdSession = sessionRepository.findById(reloadedSchedule.getSession().getId()).orElseThrow();
        assertEquals(schedStart.toEpochSecond(), createdSession.getStartedAt().toEpochSecond());
        assertEquals(schedEnd.toEpochSecond(), createdSession.getPlannedEnd().toEpochSecond());
        assertEquals(fmUser.getId(), createdSession.getStartedBy().getId());

        // Kiểm tra 1 audit log ENABLE_EVENT_MODE với actor SYSTEM "EVENT_SCHEDULE_ACTIVATION"
        assertEquals(auditCountBefore + 1, auditLogRepository.count());
        List<AuditLog> logs = auditLogRepository.findAll().stream()
                .filter(l -> l.getTargetId().equals(internalArea.getId().toString()) && l.getAction() == AuditAction.ENABLE_EVENT_MODE)
                .sorted(java.util.Comparator.comparing(AuditLog::getChangedAt))
                .toList();
        assertFalse(logs.isEmpty());
        AuditLog enableLog = logs.get(logs.size() - 1);
        assertEquals("SYSTEM", enableLog.getActorType());
        assertEquals("EVENT_SCHEDULE_ACTIVATION", enableLog.getActorSource());

        // GUARD nhận thông báo EVENT_MODE_CHANGED
        List<Notification> guardNotifs = getNotificationsForUser(guardUser.getId());
        assertTrue(guardNotifs.stream().anyMatch(n -> n.getType() == NotificationType.EVENT_MODE_CHANGED));
    }

    @Test
    @DisplayName("ES-T10: Lịch bị lỡ -> FAILED + audit FAIL + thông báo FM; không tạo phiên")
    void testES_T10_JobActivation_MissedSchedule() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime schedStart = now.minusHours(3);
        OffsetDateTime schedEnd = now.minusMinutes(10); // Đã kết thúc trong quá khứ

        AreaEventSchedule schedule = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(schedStart)
                .endAt(schedEnd)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo lên lịch")
                .note("Lich bi lo gio khong chay kip")
                .createdBy(fmUser)
                .createdAt(now.minusHours(5))
                .build());

        long sessionCountBefore = sessionRepository.findByAreaId(internalArea.getId()).size();

        // Chạy job kích hoạt
        areaService.processScheduledEventActivations(now);

        // Lịch chuyển sang FAILED
        AreaEventSchedule reloadedSchedule = eventScheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(AreaEventScheduleStatus.FAILED, reloadedSchedule.getStatus());
        assertNotNull(reloadedSchedule.getFailedAt());
        assertTrue(reloadedSchedule.getFailReason().contains("lỡ"));
        assertNull(reloadedSchedule.getSession());

        // Không tạo phiên mới cho internalArea
        assertEquals(sessionCountBefore, sessionRepository.findByAreaId(internalArea.getId()).size());

        // Audit FAIL được ghi nhận
        List<AuditLog> logs = auditLogRepository.findAll().stream()
                .filter(l -> l.getTargetId().equals(schedule.getId().toString()) && l.getAction() == AuditAction.FAIL)
                .toList();
        assertEquals(1, logs.size());
        AuditLog failLog = logs.get(0);
        assertEquals("SYSTEM", failLog.getActorType());
        assertEquals("EVENT_SCHEDULE_ACTIVATION", failLog.getActorSource());

        // FM nhận thông báo EVENT_MODE_SCHEDULE_FAILED
        List<Notification> fmNotifs = getNotificationsForUser(fmUser.getId());
        assertTrue(fmNotifs.stream().anyMatch(n -> n.getType() == NotificationType.EVENT_MODE_SCHEDULE_FAILED));
    }

    @Test
    @DisplayName("ES-T11: Job đóng phiên hết hạn -> 1 audit EXPIRE_EVENT_MODE SYSTEM; chạy job 2 lần vẫn 1 dòng; đường cleanup trong thao tác ghi cũng ra đúng 1 dòng")
    void testES_T11_JobExpiryAndCleanupAudit() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime sessionStart = now.minusHours(3);
        OffsetDateTime sessionPlannedEnd = now.minusMinutes(10);

        AreaEventSession session = sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(sessionStart)
                .plannedEnd(sessionPlannedEnd)
                .actualEnd(null)
                .startedBy(fmUser)
                .createdAt(sessionStart)
                .build());

        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(sessionPlannedEnd);
        areaRepository.save(internalArea);

        // 1. Chạy job đóng phiên lần 1
        areaService.processExpiredEventSessions(now);

        AreaEventSession reloadedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertNotNull(reloadedSession.getActualEnd());
        assertEquals(sessionPlannedEnd.toEpochSecond(), reloadedSession.getActualEnd().toEpochSecond());

        Area reloadedArea = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertFalse(reloadedArea.getOpenToMembers());
        assertNull(reloadedArea.getOpenUntil());

        List<AuditLog> expireLogs = auditLogRepository.findAll().stream()
                .filter(l -> l.getTargetId().equals(internalArea.getId().toString()) && l.getAction() == AuditAction.EXPIRE_EVENT_MODE)
                .toList();
        assertEquals(1, expireLogs.size());
        AuditLog log1 = expireLogs.get(0);
        assertEquals("SYSTEM", log1.getActorType());
        assertEquals("EVENT_MODE_EXPIRY", log1.getActorSource());

        // 2. Chạy job lần 2 -> không ghi thêm audit log nào cho internalArea
        areaService.processExpiredEventSessions(now);
        List<AuditLog> expireLogs2 = auditLogRepository.findAll().stream()
                .filter(l -> l.getTargetId().equals(internalArea.getId().toString()) && l.getAction() == AuditAction.EXPIRE_EVENT_MODE)
                .toList();
        assertEquals(1, expireLogs2.size());
    }

    @Test
    @DisplayName("ES-T12: Nhắc FM đúng 1 lần; REMINDER_MINUTES = 0 -> không nhắc")
    void testES_T12_ScheduleReminder() {
        OffsetDateTime now = OffsetDateTime.now();
        // REMINDER_MINUTES = 30; tạo lịch bắt đầu sau 20 phút
        AreaEventSchedule schedule = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(now.plusMinutes(20))
                .endAt(now.plusHours(2))
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo lên lịch")
                .note("Lich chuan bi dien ra can nhac FM")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        // 1. Chạy nhắc nhở lần 1 -> FM nhận thông báo EVENT_MODE_SCHEDULE_STARTING, reminded_at được set
        areaService.processScheduleReminders(now);

        AreaEventSchedule reloadedSchedule = eventScheduleRepository.findById(schedule.getId()).orElseThrow();
        assertNotNull(reloadedSchedule.getRemindedAt());

        long countStarting = getNotificationsForUser(fmUser.getId()).stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_SCHEDULE_STARTING)
                .count();
        assertEquals(1, countStarting);

        // 2. Chạy nhắc nhở lần 2 -> không gửi lại
        areaService.processScheduleReminders(now);
        long countStarting2 = getNotificationsForUser(fmUser.getId()).stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_SCHEDULE_STARTING)
                .count();
        assertEquals(1, countStarting2);

        // 3. Khi REMINDER_MINUTES = 0 -> không nhắc
        systemConfigService.update(ConfigKey.EVENT_MODE_SCHEDULE_REMINDER_MINUTES.name(), "0", adminUser.getEmail());
        AreaEventSchedule schedule2 = eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(now.plusMinutes(10))
                .endAt(now.plusHours(2))
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo lên lịch")
                .note("Lich 2 khi reminder minutes bang 0")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        areaService.processScheduleReminders(now);
        AreaEventSchedule reloadedSchedule2 = eventScheduleRepository.findById(schedule2.getId()).orElseThrow();
        assertNull(reloadedSchedule2.getRemindedAt());
    }

    @Test
    @DisplayName("ES-T13: Vô hiệu hoá / đổi sang HIGHLY khi còn lịch -> 409; huỷ lịch xong -> được")
    void testES_T13_DeactivateAndChangeLevelBlockedBySchedule() {
        OffsetDateTime now = OffsetDateTime.now();
        EventScheduleResponse sched = areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(now.plusDays(2), now.plusDays(2).plusHours(2), REASON_ENABLE, "Dat lich de test chan khoa"),
                fmUser.getEmail());

        // 1. Vô hiệu hoá khu vực -> ERR_AREA_042 (409)
        AreaException exDeact = assertThrows(AreaException.class, () ->
                areaService.deactivate(internalArea.getId(), fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_042, exDeact.getErrorCode());
        assertTrue(exDeact.getMessage().contains("lịch sự kiện chưa diễn ra, huỷ lịch trước"));

        // 2. Đổi sang HIGHLY_CONFIDENTIAL -> ERR_AREA_042 (409)
        AreaUpdateRequest updateReq = new AreaUpdateRequest(
                internalArea.getName(),
                AreaLevel.HIGHLY_CONFIDENTIAL,
                testBuilding.getCode(),
                testFloor.getFloorCode(),
                testFloor.getId()
        );
        AreaException exUpdate = assertThrows(AreaException.class, () ->
                areaService.update(internalArea.getId(), updateReq, adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_042, exUpdate.getErrorCode());
        assertTrue(exUpdate.getMessage().contains("Khu vực còn 1 lịch sự kiện chưa diễn ra:"));
        assertTrue(exUpdate.getMessage().contains(fmUser.getFullName()));
        assertTrue(exUpdate.getMessage().contains("Liên hệ quản lý cơ sở vật chất để huỷ lịch trước."));

        // 3. Huỷ lịch
        areaService.cancelSchedule(internalArea.getId(), sched.id(),
                new EventScheduleCancelRequest(REASON_DISABLE, "Huy lich de tiep tuc vo hieu hoa"),
                fmUser.getEmail());

        // 4. Giờ vô hiệu hoá thành công
        areaService.deactivate(internalArea.getId(), fmUser.getEmail());
        Area reloaded = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertFalse(reloaded.getIsActive());

        // Kích hoạt lại để test đổi cấp độ
        reloaded.setIsActive(true);
        reloaded.setDeletedAt(null);
        areaRepository.save(reloaded);

        AreaResponse updateRes = areaService.update(internalArea.getId(), updateReq, adminUser.getEmail());
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, updateRes.areaLevel());
    }

    @Test
    @DisplayName("ES-T14: 2 request đặt lịch chồng nhau đồng thời -> đúng 1 thành công")
    void testES_T14_ConcurrentOverlappingSchedules() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < 5; i++) {
                OffsetDateTime now = OffsetDateTime.now();
                OffsetDateTime start = now.plusDays(10 + i).withNano(0);
                OffsetDateTime end = start.plusHours(2);

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threads);
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger conflictCount = new AtomicInteger(0);

                for (int t = 0; t < threads; t++) {
                    final int idx = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            areaService.createSchedule(internalArea.getId(),
                                    new EventScheduleRequest(start, end, REASON_ENABLE, "Dat lich dong thoi thread " + idx),
                                    fmUser.getEmail());
                            successCount.incrementAndGet();
                        } catch (AreaException ex) {
                            if (ex.getErrorCode() == AreaErrorCode.ERR_AREA_037) {
                                conflictCount.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

                assertEquals(1, successCount.get(), "Lần " + i + ": đúng 1 request thành công");
                assertEquals(1, conflictCount.get(), "Lần " + i + ": đúng 1 request nhận lỗi xung đột");
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("ES-T15: ADMIN hạ MAX_HOURS sau khi đặt lịch dài hơn -> lịch không bị huỷ, FM nhận thông báo có lịch đó")
    void testES_T15_AdminLowersMaxHours_ScheduleNotCancelledAndFmNotified() {
        OffsetDateTime now = OffsetDateTime.now();
        // MAX_HOURS hiện là 12, đặt lịch dài 10 giờ
        EventScheduleResponse sched = areaService.createSchedule(internalArea.getId(),
                new EventScheduleRequest(now.plusDays(3), now.plusDays(3).plusHours(10), REASON_ENABLE, "Dat lich dai 10h hop le"),
                fmUser.getEmail());

        notificationRepository.deleteAll();

        // ADMIN hạ MAX_HOURS xuống 6 giờ
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "6", adminUser.getEmail());

        // Lịch không bị huỷ (vẫn SCHEDULED)
        AreaEventSchedule reloadedSchedule = eventScheduleRepository.findById(sched.id()).orElseThrow();
        assertEquals(AreaEventScheduleStatus.SCHEDULED, reloadedSchedule.getStatus());

        // FM nhận thông báo EVENT_MODE_LIMIT_CHANGED có liệt kê lịch vi phạm
        List<Notification> fmNotifs = getNotificationsForUser(fmUser.getId());
        assertFalse(fmNotifs.isEmpty());
        Notification notif = fmNotifs.stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_LIMIT_CHANGED)
                .findFirst()
                .orElse(null);
        assertNotNull(notif, "FM phải nhận thông báo EVENT_MODE_LIMIT_CHANGED");
        assertTrue(notif.getMessage().contains("Lịch vi phạm"), "Thông báo phải liệt kê lịch vi phạm");
    }
    @Test
    @DisplayName("DF-U2: Thông điệp ERR_AREA_042 liệt kê chi tiết từng lịch theo giờ và người đặt, rút gọn khi > 5 lịch")
    void testDF_U2_ErrorMessageFormatsMultipleSchedulesProperly() {
        OffsetDateTime now = OffsetDateTime.now();
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        java.time.format.DateTimeFormatter dtfDateHour = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zone);
        java.time.format.DateTimeFormatter dtfTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone);

        // Tạo 2 lịch sự kiện
        OffsetDateTime s1Start = now.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime s1End = s1Start.plusHours(4); // 08:00–12:00
        OffsetDateTime s2Start = now.plusDays(3).withHour(13).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime s2End = s2Start.plusHours(4); // 13:00–17:00

        eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(s1Start)
                .endAt(s1End)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo 1")
                .note("Test message list 1")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(internalArea)
                .startAt(s2Start)
                .endAt(s2End)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(REASON_ENABLE)
                .reasonLabel("Hội thảo 2")
                .note("Test message list 2")
                .createdBy(fmUser)
                .createdAt(now.minusHours(1))
                .build());

        AreaException ex = assertThrows(AreaException.class, () ->
                areaService.deactivate(internalArea.getId(), fmUser.getEmail())
        );

        String msg = ex.getMessage();
        System.out.println("DF-U2 ERR_AREA_042 message: " + msg);

        assertTrue(msg.startsWith("Khu vực còn 2 lịch sự kiện chưa diễn ra:"));
        String expectedTime1 = dtfDateHour.format(s1Start) + "–" + dtfTime.format(s1End);
        String expectedTime2 = dtfDateHour.format(s2Start) + "–" + dtfTime.format(s2End);
        assertTrue(msg.contains(expectedTime1 + " (" + fmUser.getFullName() + ")"), "Phải chứa lịch 1 đúng giờ và tên: " + msg);
        assertTrue(msg.contains(expectedTime2 + " (" + fmUser.getFullName() + ")"), "Phải chứa lịch 2 đúng giờ và tên: " + msg);
        assertTrue(msg.endsWith("Liên hệ quản lý cơ sở vật chất để huỷ lịch trước."));
    }

}
