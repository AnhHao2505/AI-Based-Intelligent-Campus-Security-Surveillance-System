package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AccessControlAuditLogRepository;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.ReasonCatalogAuditSnapshot;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogCreateRequest;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogUpdateRequest;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.ReasonCatalog;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.repository.AreaEventSessionRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.ReasonCatalogRepository;
import com.fa26se040.icss.service.ReasonCatalogService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class Step5aIntegrationTest extends AbstractIntegrationTest {

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
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private AccessControlAuditLogRepository auditLogRepository;

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private AccessRequestService accessRequestService;

    @Autowired
    private AreaService areaService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AreaEventSessionRepository sessionRepository;

    @Autowired
    private ReasonCatalogRepository reasonCatalogRepository;

    @Autowired
    private ReasonCatalogService reasonCatalogService;

    @Autowired
    private NotificationRepository notificationRepository;

    private Building testBuilding;
    private Floor testFloor;

    private User userL1;
    private User userL2;
    private User userL3;
    private User fmUser;
    private User adminUser;

    private Area publicArea;
    private Area internalArea;
    private Area contactArea;
    private Area highlyArea;

    @BeforeEach
    void setUpData() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        testBuilding = buildingRepository.findByCodeIgnoreCase("TEST_BLD")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test Step 5")
                        .code("TEST_BLD")
                        .isActive(true)
                        .build()));

        testFloor = floorRepository.findByBuildingIdAndFloorCodeIgnoreCase(testBuilding.getId(), "F1")
                .orElseGet(() -> floorRepository.save(Floor.builder()
                        .name("Tầng 1 Test")
                        .floorCode("F1")
                        .floorOrder(1)
                        .building(testBuilding)
                        .isActive(true)
                        .build()));

        userL1 = userRepository.save(User.builder()
                .email("test.l1." + suffix + "@fpt.edu.vn")
                .userCode("SV_L1_" + suffix)
                .fullName("Sinh Viên L1 " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        userL2 = userRepository.save(User.builder()
                .email("test.l2." + suffix + "@fpt.edu.vn")
                .userCode("SV_L2_" + suffix)
                .fullName("Nhân Viên L2 " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(true)
                .build());

        userL3 = userRepository.save(User.builder()
                .email("test.l3." + suffix + "@fpt.edu.vn")
                .userCode("SV_L3_" + suffix)
                .fullName("Quản Lý L3 " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build());

        fmUser = userRepository.save(User.builder()
                .email("test.fm." + suffix + "@fpt.edu.vn")
                .userCode("FM_" + suffix)
                .fullName("Facility Manager " + suffix)
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        adminUser = userRepository.save(User.builder()
                .email("test.admin." + suffix + "@fpt.edu.vn")
                .userCode("ADM_" + suffix)
                .fullName("Admin " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        publicArea = areaRepository.save(Area.builder()
                .name("Khu Vực Public " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        internalArea = areaRepository.save(Area.builder()
                .name("Khu Vực Internal " + suffix)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        contactArea = areaRepository.save(Area.builder()
                .name("Khu Vực Contact " + suffix)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        highlyArea = areaRepository.save(Area.builder()
                .name("Khu Vực Highly " + suffix)
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "12", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_WINDOW_DAYS.name(), "7", adminUser.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", adminUser.getEmail());
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("D1: Ma trận checkEntry 4 loại × 3 cấp user (không AP, không đơn, không sự kiện)")
    void testD1_CheckEntryMatrix() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. PUBLIC: cả 3 cấp đều được vào bằng ACCESS_LEVEL
        AccessDecision dPublicL1 = accessDecisionService.checkEntry(userL1.getId(), publicArea.getId(), now);
        assertTrue(dPublicL1.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dPublicL1.source());

        AccessDecision dPublicL2 = accessDecisionService.checkEntry(userL2.getId(), publicArea.getId(), now);
        assertTrue(dPublicL2.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dPublicL2.source());

        AccessDecision dPublicL3 = accessDecisionService.checkEntry(userL3.getId(), publicArea.getId(), now);
        assertTrue(dPublicL3.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dPublicL3.source());

        // 2. INTERNAL: L1 bị từ chối; L2, L3 được vào bằng ACCESS_LEVEL
        AccessDecision dInternalL1 = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now);
        assertFalse(dInternalL1.allowed());
        assertEquals(AccessSource.NONE, dInternalL1.source());

        AccessDecision dInternalL2 = accessDecisionService.checkEntry(userL2.getId(), internalArea.getId(), now);
        assertTrue(dInternalL2.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dInternalL2.source());

        AccessDecision dInternalL3 = accessDecisionService.checkEntry(userL3.getId(), internalArea.getId(), now);
        assertTrue(dInternalL3.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dInternalL3.source());

        // 3. CONTACT: Cả 3 cấp đều bị từ chối (explicit = true)
        AccessDecision dContactL1 = accessDecisionService.checkEntry(userL1.getId(), contactArea.getId(), now);
        assertFalse(dContactL1.allowed());
        assertEquals(AccessSource.NONE, dContactL1.source());

        AccessDecision dContactL2 = accessDecisionService.checkEntry(userL2.getId(), contactArea.getId(), now);
        assertFalse(dContactL2.allowed());
        assertEquals(AccessSource.NONE, dContactL2.source());

        AccessDecision dContactL3 = accessDecisionService.checkEntry(userL3.getId(), contactArea.getId(), now);
        assertFalse(dContactL3.allowed());
        assertEquals(AccessSource.NONE, dContactL3.source());

        // 4. HIGHLY: Cả 3 cấp đều bị từ chối (explicit = true)
        AccessDecision dHighlyL1 = accessDecisionService.checkEntry(userL1.getId(), highlyArea.getId(), now);
        assertFalse(dHighlyL1.allowed());
        assertEquals(AccessSource.NONE, dHighlyL1.source());

        AccessDecision dHighlyL2 = accessDecisionService.checkEntry(userL2.getId(), highlyArea.getId(), now);
        assertFalse(dHighlyL2.allowed());
        assertEquals(AccessSource.NONE, dHighlyL2.source());

        AccessDecision dHighlyL3 = accessDecisionService.checkEntry(userL3.getId(), highlyArea.getId(), now);
        assertFalse(dHighlyL3.allowed());
        assertEquals(AccessSource.NONE, dHighlyL3.source());
    }

    @Test
    @DisplayName("D2: BR-RQ-01 tạo đơn cá nhân và nhóm")
    void testD2_BrRq01_CreateRequest() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        // L1 gửi đơn vào INTERNAL (yêu cầu level 2) -> 400
        IndividualAccessRequestCreateRequest reqL1Internal = new IndividualAccessRequestCreateRequest(
                internalArea.getId(), start, end, "L1 vao internal"
        );
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(reqL1Internal, userL1.getEmail())
        );
        assertTrue(ex1.getMessage().contains(userL1.getFullName()));
        assertTrue(ex1.getMessage().contains(userL1.getUserCode()));

        // L2 gửi vào CONTACT (yêu cầu level 2) -> OK
        IndividualAccessRequestCreateRequest reqL2Contact = new IndividualAccessRequestCreateRequest(
                contactArea.getId(), start, end, "L2 vao contact"
        );
        var respL2Contact = accessRequestService.createIndividualRequest(reqL2Contact, userL2.getEmail());
        assertNotNull(respL2Contact);
        assertEquals(RequestStatus.PENDING, respL2Contact.status());

        // L2 gửi vào HIGHLY (yêu cầu level 3) -> 400
        IndividualAccessRequestCreateRequest reqL2Highly = new IndividualAccessRequestCreateRequest(
                highlyArea.getId(), start, end, "L2 vao highly"
        );
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(reqL2Highly, userL2.getEmail())
        );
        assertTrue(ex2.getMessage().contains(userL2.getFullName()));
        assertTrue(ex2.getMessage().contains(userL2.getUserCode()));

        // Đơn nhóm L2 có 1 thành viên L1 vào CONTACT -> 400 và nêu thành viên L1
        GroupAccessRequestCreateRequest groupReq = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start.plusHours(3),
                end.plusHours(3),
                "Nhom L2 va L1",
                List.of(userL1.getUserCode())
        );
        IllegalArgumentException exGroup = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createGroupRequest(groupReq, userL2.getEmail())
        );
        assertTrue(exGroup.getMessage().contains(userL1.getFullName()));
        assertTrue(exGroup.getMessage().contains(userL1.getUserCode()));
    }

    @Test
    @DisplayName("D3: BR-RQ-02 FM duyệt đơn (kiểm tra lại cấp hiện tại và cờ group)")
    void testD3_BrRq02_ApproveRequest() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        // 1. Tạo đơn hợp lệ (L2 vào CONTACT)
        var createResp = accessRequestService.createIndividualRequest(
                new IndividualAccessRequestCreateRequest(contactArea.getId(), start, end, "Hop le ban dau"),
                userL2.getEmail()
        );
        UUID reqId = createResp.id();

        // Hạ userL2 về L1
        userL2.setAccessLevel(1);
        userRepository.save(userL2);

        // FM duyệt -> bị chặn, ném ngoại lệ
        AccessRequestReviewRequest approveReq = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        IllegalArgumentException exLowered = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.reviewRequest(reqId, approveReq, fmUser.getEmail())
        );
        assertTrue(exLowered.getMessage().contains(userL2.getFullName()));

        // Kiểm tra đơn vẫn PENDING trong DB
        AccessRequest currentReq = accessRequestRepository.findById(reqId).orElseThrow();
        assertEquals(RequestStatus.PENDING, currentReq.getStatus());

        // 2. Đơn GROUP vào HIGHLY tạo khi cờ = true, rồi cờ = false -> FM duyệt bị chặn
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), "true", adminUser.getEmail());

        var groupResp = accessRequestService.createGroupRequest(
                new GroupAccessRequestCreateRequest(
                        highlyArea.getId(),
                        start.plusHours(4),
                        end.plusHours(4),
                        "Nhom highly",
                        List.of(userL3.getUserCode())
                ),
                fmUser.getEmail() // requester level 3, member level 3
        );
        UUID groupReqId = groupResp.id();

        // Đổi cấu hình thành false
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), "false", adminUser.getEmail());

        // FM duyệt -> bị chặn
        IllegalArgumentException exGroupBlocked = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.reviewRequest(groupReqId, approveReq, fmUser.getEmail())
        );
        assertTrue(exGroupBlocked.getMessage().contains("HIGHLY_CONFIDENTIAL"));

        // Kiểm tra đơn vẫn PENDING
        AccessRequest currentGroupReq = accessRequestRepository.findById(groupReqId).orElseThrow();
        assertEquals(RequestStatus.PENDING, currentGroupReq.getStatus());
    }

    @Test
    @DisplayName("D4: Chế độ sự kiện - FM quản lý và kiểm toán")
    void testD4_EventModeManagementAndAudit() throws Exception {
        OffsetDateTime futureUntil = OffsetDateTime.now().plusHours(4);

        long auditCountBefore = auditLogRepository.count();

        // FM bật cho INTERNAL (có lý do, openUntil tương lai) -> OK + 1 dòng audit
        AreaEventModeUpdateRequest validReq = new AreaEventModeUpdateRequest(true, futureUntil, "SEMINAR", "Mở sự kiện Workshop chuyên môn cho sinh viên");
        var respInternal = areaService.updateEventMode(internalArea.getId(), validReq, fmUser.getEmail());
        assertTrue(respInternal.openToMembers());
        assertTrue(respInternal.eventActive());
        assertEquals(auditCountBefore + 1, auditLogRepository.count());

        AccessControlAuditLog log = auditLogRepository.findAll().get((int) auditCountBefore);
        assertEquals(AccessControlAction.ENABLE_EVENT_MODE, log.getAction());
        assertEquals(internalArea.getId().toString(), log.getTargetId());

        // Bật cho HIGHLY -> 400 (ERR_AREA_022)
        AreaException exHighly = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(highlyArea.getId(), validReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_022, exHighly.getErrorCode());

        // Bật cho PUBLIC -> 400 (ERR_AREA_022)
        AreaException exPublic = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(publicArea.getId(), validReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_022, exPublic.getErrorCode());

        // ADMIN gọi endpoint -> 403 Forbidden
        String adminToken = jwtTokenProvider.generateToken(adminUser);
        mockMvc.perform(patch("/api/areas/" + internalArea.getId() + "/event-mode")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isForbidden());

        // Thiếu reasonCode hoặc note -> 400 (ERR_AREA_024)
        AreaEventModeUpdateRequest noReasonReq = new AreaEventModeUpdateRequest(true, futureUntil, null, "   ");
        AreaException exNoReason = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(), noReasonReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exNoReason.getErrorCode());

        // openUntil quá khứ -> 400 (ERR_AREA_023)
        AreaEventModeUpdateRequest pastUntilReq = new AreaEventModeUpdateRequest(true, OffsetDateTime.now().minusHours(1), "EVENT_PROLONGED", "Lý do hợp lệ trên mười ký tự");
        AreaException exPast = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(), pastUntilReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_023, exPast.getErrorCode());

        // Bật lại y hệt (gửi EVENT_EXTEND với cùng openUntil) -> no-op không thêm audit log (BR-EV-08)
        long auditCountAfterAll = auditLogRepository.count();
        AreaEventModeUpdateRequest noOpReq = new AreaEventModeUpdateRequest(true, futureUntil, "EVENT_PROLONGED", "Gia hạn giữ nguyên giờ không đổi");
        areaService.updateEventMode(internalArea.getId(), noOpReq, fmUser.getEmail());
        assertEquals(auditCountAfterAll, auditLogRepository.count());
    }

    @Test
    @DisplayName("D5: checkEntry với chế độ sự kiện (L1 vào INTERNAL, hết hạn, sau khi tắt)")
    void testD5_CheckEntryWithEventMode() {
        OffsetDateTime futureUntil = OffsetDateTime.now().plusHours(2);

        // Bật sự kiện cho INTERNAL
        areaService.updateEventMode(internalArea.getId(), new AreaEventModeUpdateRequest(true, futureUntil, "SEMINAR", "Mở ngày hội kỹ thuật cho sinh viên"), fmUser.getEmail());

        // L1 vào INTERNAL trong thời gian sự kiện -> allow OPEN_EVENT
        OffsetDateTime now = OffsetDateTime.now();
        AccessDecision decisionValid = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now);
        assertTrue(decisionValid.allowed());
        assertEquals(AccessSource.OPEN_EVENT, decisionValid.source());

        // at >= open_until -> deny
        OffsetDateTime expiredAt = futureUntil.plusMinutes(1);
        AccessDecision decisionExpired = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), expiredAt);
        assertFalse(decisionExpired.allowed());
        assertEquals(AccessSource.NONE, decisionExpired.source());

        // Sau khi tắt -> deny ngay tại thời điểm now
        areaService.updateEventMode(internalArea.getId(), new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Kết thúc sự kiện sớm hơn dự kiến"), fmUser.getEmail());
        AccessDecision decisionDisabled = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now);
        assertFalse(decisionDisabled.allowed());
        assertEquals(AccessSource.NONE, decisionDisabled.source());
    }

    @Test
    @DisplayName("T1: Bật hợp lệ -> audit targetType AREA_EVENT_MODE, snapshot có reasonCode + reasonLabel + note; 1 phiên mới")
    void testT1_EnableValidEventMode() {
        OffsetDateTime futureUntil = OffsetDateTime.now().plusHours(3);
        long auditBefore = auditLogRepository.count();
        long sessionBefore = sessionRepository.count();

        AreaEventModeUpdateRequest req = new AreaEventModeUpdateRequest(
                true, futureUntil, "SEMINAR", "Hội thảo nghiên cứu an ninh thông tin"
        );
        var resp = areaService.updateEventMode(contactArea.getId(), req, fmUser.getEmail());
        assertTrue(resp.openToMembers());
        assertTrue(resp.eventActive());

        assertEquals(auditBefore + 1, auditLogRepository.count());
        AccessControlAuditLog log = auditLogRepository.findAll().get((int) auditBefore);
        assertEquals(AccessControlTargetType.AREA_EVENT_MODE, log.getTargetType());
        assertEquals(AccessControlAction.ENABLE_EVENT_MODE, log.getAction());
        assertEquals(contactArea.getId().toString(), log.getTargetId());
        assertEquals("Hội thảo nghiên cứu an ninh thông tin", log.getReason());

        AreaEventModeAuditSnapshot newSnap = objectMapper.convertValue(log.getNewValue(), AreaEventModeAuditSnapshot.class);
        assertTrue(newSnap.openToMembers());
        assertEquals("SEMINAR", newSnap.reasonCode());
        assertEquals("Hội thảo/sự kiện chuyên môn", newSnap.reasonLabel());
        assertEquals("Hội thảo nghiên cứu an ninh thông tin", newSnap.note());

        assertEquals(sessionBefore + 1, sessionRepository.count());
        var openSessionOpt = sessionRepository.findByAreaIdAndActualEndIsNull(contactArea.getId());
        assertTrue(openSessionOpt.isPresent());
        assertEquals(futureUntil.toEpochSecond(), openSessionOpt.get().getPlannedEnd().toEpochSecond());
    }

    @Test
    @DisplayName("T2: reasonCode sai action_type / đã ngừng dùng / không tồn tại -> 400; note 9 ký tự -> 400; note 501 -> 400")
    void testT2_ReasonCodeAndNoteValidations() {
        OffsetDateTime futureUntil = OffsetDateTime.now().plusHours(3);

        // 1. reasonCode sai action_type (ENDED_EARLY là của EVENT_DISABLE, dùng khi bật) -> ERR_AREA_030 (409 M1)
        AreaException exWrongAction = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, futureUntil, "ENDED_EARLY", "Ghi chu hop le tren 10 ky tu"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_030, exWrongAction.getErrorCode());

        // 2. reasonCode đã ngừng dùng -> ERR_AREA_025
        String tempDeactivatedCode = "TEMP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ReasonCatalog customReason = reasonCatalogRepository.save(ReasonCatalog.builder()
                .actionType("EVENT_ENABLE")
                .code(tempDeactivatedCode)
                .label("Tam ngung dung")
                .isActive(false)
                .isOther(false)
                .sortOrder(99)
                .build());
        AreaException exDeactivated = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, futureUntil, tempDeactivatedCode, "Ghi chu hop le tren 10 ky tu"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_025, exDeactivated.getErrorCode());

        // 3. reasonCode không tồn tại -> ERR_AREA_025
        AreaException exNotExist = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, futureUntil, "NON_EXISTENT_CODE", "Ghi chu hop le tren 10 ky tu"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_025, exNotExist.getErrorCode());

        // 4. note 9 ký tự -> ERR_AREA_024
        AreaException exNote9 = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, futureUntil, "SEMINAR", "123456789"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exNote9.getErrorCode());

        // 5. note 501 ký tự -> ERR_AREA_024
        String note501 = "a".repeat(501);
        AreaException exNote501 = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, futureUntil, "SEMINAR", note501),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exNote501.getErrorCode());
    }

    @Test
    @DisplayName("T3: openUntil = now + MAX_HOURS + 1 phút -> 400")
    void testT3_MaxHoursValidation() {
        // Cấu hình mặc định EVENT_MODE_MAX_HOURS = 12
        OffsetDateTime overMaxTime = OffsetDateTime.now().plusHours(12).plusMinutes(1);

        AreaException exOver = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, overMaxTime, "SEMINAR", "Ghi chu vuot qua so gio toi da"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_027, exOver.getErrorCode());
    }

    @Test
    @DisplayName("T4: Ngân sách: với BUDGET=48, WINDOW=7: chuỗi phiên tổng 40h trong 7 ngày -> bật thêm 10h bị chặn, 8h cho qua")
    void testT4_BudgetSlidingWindowValidation() {
        OffsetDateTime now = OffsetDateTime.now();
        // Dọn các phiên cũ của contactArea
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(contactArea.getId()))
                .toList());

        // Tạo 2 phiên đã kết thúc trong vòng 7 ngày: mỗi phiên 20h -> tổng 40h
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

        // Bật thêm 10h (tổng sẽ là 40 + 10 = 50h > 48h) -> bị chặn
        AreaException exBudget = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(contactArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(10), "SEMINAR", "Thu mo them 10 gio vuot ngan sach"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_028, exBudget.getErrorCode());
        // Thông điệp nêu số giờ đã dùng và số giờ còn lại
        assertTrue(exBudget.getMessage().contains("40.0") || exBudget.getMessage().contains("40"));
        assertTrue(exBudget.getMessage().contains("8.0") || exBudget.getMessage().contains("8"));

        // Bật thêm 8h (tổng 40 + 8 = 48h <= 48h) -> cho qua thành công
        var successResp = areaService.updateEventMode(contactArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(8), "SEMINAR", "Mo them 8 gio vua van ngan sach"),
                fmUser.getEmail());
        assertTrue(successResp.openToMembers());
    }

    @Test
    @DisplayName("T5: Gia hạn: phiên cũ actual_end = now, phiên mới tạo, audit EXTEND_EVENT_MODE; gia hạn thiếu reasonCode -> 400")
    void testT5_ExtendEventMode() {
        OffsetDateTime now = OffsetDateTime.now();
        // Bật sự kiện đến now + 3h
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(3), "SEMINAR", "Bat su kien ban dau de gia han"),
                fmUser.getEmail());

        var initialSessionOpt = sessionRepository.findByAreaIdAndActualEndIsNull(internalArea.getId());
        assertTrue(initialSessionOpt.isPresent());
        AreaEventSession oldSession = initialSessionOpt.get();

        long auditBefore = auditLogRepository.count();

        // 1. Gia hạn thiếu reasonCode -> 400 (ERR_AREA_024)
        AreaException exNoReason = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(),
                        new AreaEventModeUpdateRequest(true, now.plusHours(6), null, "Gia han them gio vi su kien keo dai"),
                        fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exNoReason.getErrorCode());

        // 2. Gia hạn hợp lệ
        OffsetDateTime newEnd = now.plusHours(6);
        var extendResp = areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, newEnd, "EVENT_PROLONGED", "Gia han su kien vi chuong trinh keo dai"),
                fmUser.getEmail());
        assertTrue(extendResp.openToMembers());

        // Audit EXTEND_EVENT_MODE
        assertEquals(auditBefore + 1, auditLogRepository.count());
        AccessControlAuditLog extendLog = auditLogRepository.findAll().get((int) auditBefore);
        assertEquals(AccessControlAction.EXTEND_EVENT_MODE, extendLog.getAction());
        assertEquals(AccessControlTargetType.AREA_EVENT_MODE, extendLog.getTargetType());

        // Phiên cũ đã đóng (actualEnd != null)
        AreaEventSession reloadedOld = sessionRepository.findById(oldSession.getId()).orElseThrow();
        assertNotNull(reloadedOld.getActualEnd());

        // Phiên mới được tạo
        var currentActiveOpt = sessionRepository.findByAreaIdAndActualEndIsNull(internalArea.getId());
        assertTrue(currentActiveOpt.isPresent());
        AreaEventSession newSession = currentActiveOpt.get();
        assertFalse(newSession.getId().equals(oldSession.getId()));
        assertEquals(newEnd.toEpochSecond(), newSession.getPlannedEnd().toEpochSecond());
    }

    @Test
    @DisplayName("T6: Tắt -> actual_end = now; phiên quá hạn tự đóng khi có thao tác kế tiếp")
    void testT6_DisableAndAutoCloseExpiredSession() {
        OffsetDateTime now = OffsetDateTime.now();
        // Bật sự kiện
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Bat su kien de test tat"),
                fmUser.getEmail());

        var activeSessionOpt = sessionRepository.findByAreaIdAndActualEndIsNull(internalArea.getId());
        assertTrue(activeSessionOpt.isPresent());
        AreaEventSession activeSession = activeSessionOpt.get();

        // Tắt sự kiện
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Ket thuc su kien theo lich trinh"),
                fmUser.getEmail());

        AreaEventSession closedSession = sessionRepository.findById(activeSession.getId()).orElseThrow();
        assertNotNull(closedSession.getActualEnd());
        assertEquals(fmUser.getId(), closedSession.getEndedBy().getId());

        // Kiểm tra tự đóng phiên quá hạn khi có thao tác kế tiếp:
        // Tạo 1 phiên quá hạn nhân tạo với actualEnd = null và plannedEnd trong quá khứ
        AreaEventSession expiredSession = sessionRepository.save(AreaEventSession.builder()
                .area(internalArea)
                .startedAt(now.minusHours(5))
                .plannedEnd(now.minusHours(1))
                .actualEnd(null)
                .startedBy(fmUser)
                .build());

        // Thực hiện thao tác kế tiếp (bật sự kiện mới)
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(2), "SEMINAR", "Thao tac ke tiep de kich hoat auto close"),
                fmUser.getEmail());

        // expiredSession phải được tự đóng với actual_end = planned_end
        AreaEventSession reloadedExpired = sessionRepository.findById(expiredSession.getId()).orElseThrow();
        assertNotNull(reloadedExpired.getActualEnd());
        assertEquals(expiredSession.getPlannedEnd().toEpochSecond(), reloadedExpired.getActualEnd().toEpochSecond());
    }

    @Test
    @DisplayName("T7: Danh mục: ADMIN tạo/sửa nhãn/ngừng dùng/dùng lại -> mỗi thao tác 1 audit REASON_CATALOG; FM gọi -> 403; ngừng dùng mục is_other -> 400; sửa nhãn xong, log cũ vẫn giữ nhãn cũ trong snapshot")
    void testT7_ReasonCatalogLifecycleAndAudit() throws Exception {
        String adminToken = jwtTokenProvider.generateToken(adminUser);
        String fmToken = jwtTokenProvider.generateToken(fmUser);

        // 1. FM gọi API tạo danh mục -> 403 Forbidden
        mockMvc.perform(post("/api/reason-catalogs")
                        .header("Authorization", "Bearer " + fmToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReasonCatalogCreateRequest("EVENT_ENABLE", "FM_TRY", "Thu tao", 1))))
                .andExpect(status().isForbidden());

        // 2. ADMIN tạo danh mục -> 1 audit CREATE
        String exhibCode = "EXHIB_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long auditBefore = auditLogRepository.count();
        var createResp = reasonCatalogService.create(
                new ReasonCatalogCreateRequest("EVENT_ENABLE", exhibCode, "Triển lãm công nghệ", 10),
                adminUser.getEmail()
        );
        assertEquals(auditBefore + 1, auditLogRepository.count());
        AccessControlAuditLog createLog = auditLogRepository.findAll().get((int) auditBefore);
        assertEquals(AccessControlTargetType.REASON_CATALOG, createLog.getTargetType());
        assertEquals(AccessControlAction.CREATE, createLog.getAction());

        ReasonCatalogAuditSnapshot createSnap = objectMapper.convertValue(createLog.getNewValue(), ReasonCatalogAuditSnapshot.class);
        assertEquals(exhibCode, createSnap.code());
        assertEquals("Triển lãm công nghệ", createSnap.label());

        // 3. ADMIN sửa nhãn -> 1 audit UPDATE, snapshot cũ giữ "Triển lãm công nghệ"
        var updateResp = reasonCatalogService.update(
                createResp.id(),
                new ReasonCatalogUpdateRequest("Triển lãm khoa học công nghệ", 11),
                adminUser.getEmail()
        );
        assertEquals(auditBefore + 2, auditLogRepository.count());
        AccessControlAuditLog updateLog = auditLogRepository.findAll().get((int) auditBefore + 1);
        assertEquals(AccessControlAction.UPDATE, updateLog.getAction());
        ReasonCatalogAuditSnapshot updateOldSnap = objectMapper.convertValue(updateLog.getOldValue(), ReasonCatalogAuditSnapshot.class);
        ReasonCatalogAuditSnapshot updateNewSnap = objectMapper.convertValue(updateLog.getNewValue(), ReasonCatalogAuditSnapshot.class);
        assertEquals("Triển lãm công nghệ", updateOldSnap.label());
        assertEquals("Triển lãm khoa học công nghệ", updateNewSnap.label());

        // Log cũ tạo ban đầu vẫn giữ snapshot gốc
        AccessControlAuditLog origLog = auditLogRepository.findById(createLog.getId()).orElseThrow();
        ReasonCatalogAuditSnapshot origSnap = objectMapper.convertValue(origLog.getNewValue(), ReasonCatalogAuditSnapshot.class);
        assertEquals("Triển lãm công nghệ", origSnap.label());

        // 4. ADMIN ngừng dùng -> 1 audit DEACTIVATE
        reasonCatalogService.deactivate(createResp.id(), adminUser.getEmail());
        assertEquals(auditBefore + 3, auditLogRepository.count());
        AccessControlAuditLog deactLog = auditLogRepository.findAll().get((int) auditBefore + 2);
        assertEquals(AccessControlAction.DEACTIVATE, deactLog.getAction());

        // 5. ADMIN dùng lại -> 1 audit REACTIVATE
        reasonCatalogService.reactivate(createResp.id(), adminUser.getEmail());
        assertEquals(auditBefore + 4, auditLogRepository.count());
        AccessControlAuditLog reactLog = auditLogRepository.findAll().get((int) auditBefore + 3);
        assertEquals(AccessControlAction.REACTIVATE, reactLog.getAction());

        // 6. Ngừng dùng mục is_other (OTHER của EVENT_ENABLE) -> 400 (ERR_AREA_029)
        ReasonCatalog otherReason = reasonCatalogRepository.findByActionTypeAndIsOtherTrue("EVENT_ENABLE").orElseThrow();
        AreaException exDeactOther = assertThrows(AreaException.class, () ->
                reasonCatalogService.deactivate(otherReason.getId(), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_029, exDeactOther.getErrorCode());
    }

    @Test
    @DisplayName("T8: EV2: đang có sự kiện đến now+10h, hạ MAX_HOURS còn 2 -> sự kiện KHÔNG đổi; mọi FM nhận 1 thông báo EVENT_MODE_LIMIT_CHANGED; hạ config khi không có sự kiện vi phạm -> không thông báo")
    void testT8_LimitChangedNotifications() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Area> lingering = areaRepository.findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(now);
        for (Area a : lingering) {
            a.setOpenToMembers(false);
            a.setOpenUntil(null);
            areaRepository.save(a);
        }
        notificationRepository.deleteAll();

        // 1. Tạo sự kiện đến now + 10h cho internalArea
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(true, now.plusHours(10), "SEMINAR", "Su kien 10 gio truoc khi doi cau hinh"),
                fmUser.getEmail());

        long notifBefore = notificationRepository.count();

        // 2. Hạ MAX_HOURS còn 2
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "2", adminUser.getEmail());

        // Sự kiện KHÔNG đổi, phiên không bị đóng
        Area reloadedArea = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertTrue(reloadedArea.getOpenToMembers());
        assertNotNull(reloadedArea.getOpenUntil());
        assertTrue(reloadedArea.getOpenUntil().isAfter(now.plusHours(9)));

        // Mọi FM nhận thông báo EVENT_MODE_LIMIT_CHANGED
        List<Notification> newNotifs = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.EVENT_MODE_LIMIT_CHANGED)
                .toList();
        assertFalse(newNotifs.isEmpty());
        assertTrue(newNotifs.stream().anyMatch(n -> n.getRecipient().getId().equals(fmUser.getId())));
        assertTrue(newNotifs.stream().anyMatch(n -> n.getMessage().contains(internalArea.getName())));

        // Khôi phục lại MAX_HOURS = 12
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "12", adminUser.getEmail());

        // 3. Tắt sự kiện để không còn sự kiện nào vi phạm
        areaService.updateEventMode(internalArea.getId(),
                new AreaEventModeUpdateRequest(false, null, "ENDED_EARLY", "Tat su kien de test khong thong bao"),
                fmUser.getEmail());

        List<Area> stillOpen = areaRepository.findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(now);
        for (Area a : stillOpen) {
            a.setOpenToMembers(false);
            a.setOpenUntil(null);
            areaRepository.save(a);
        }

        long notifCountAfterDisable = notificationRepository.count();

        // Hạ MAX_HOURS lần nữa khi không có sự kiện vi phạm -> KHÔNG tạo thêm thông báo
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "2", adminUser.getEmail());
        assertEquals(notifCountAfterDisable, notificationRepository.count());

        // Khôi phục lại cấu hình gốc
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "12", adminUser.getEmail());
    }
}
