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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        AreaEventModeUpdateRequest validReq = new AreaEventModeUpdateRequest(true, futureUntil, "Mở sự kiện Workshop");
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

        // Thiếu reason -> 400 (ERR_AREA_024)
        AreaEventModeUpdateRequest noReasonReq = new AreaEventModeUpdateRequest(true, futureUntil, "   ");
        AreaException exNoReason = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(), noReasonReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_024, exNoReason.getErrorCode());

        // openUntil quá khứ -> 400 (ERR_AREA_023)
        AreaEventModeUpdateRequest pastUntilReq = new AreaEventModeUpdateRequest(true, OffsetDateTime.now().minusHours(1), "Lý do hợp lệ");
        AreaException exPast = assertThrows(AreaException.class, () ->
                areaService.updateEventMode(internalArea.getId(), pastUntilReq, fmUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_023, exPast.getErrorCode());

        // Bật lại y hệt -> không thêm audit log
        long auditCountAfterAll = auditLogRepository.count();
        areaService.updateEventMode(internalArea.getId(), validReq, fmUser.getEmail());
        assertEquals(auditCountAfterAll, auditLogRepository.count());
    }

    @Test
    @DisplayName("D5: checkEntry với chế độ sự kiện (L1 vào INTERNAL, hết hạn, sau khi tắt)")
    void testD5_CheckEntryWithEventMode() {
        OffsetDateTime futureUntil = OffsetDateTime.now().plusHours(2);

        // Bật sự kiện cho INTERNAL
        areaService.updateEventMode(internalArea.getId(), new AreaEventModeUpdateRequest(true, futureUntil, "Mở ngày hội kỹ thuật"), fmUser.getEmail());

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
        areaService.updateEventMode(internalArea.getId(), new AreaEventModeUpdateRequest(false, null, "Kết thúc sự kiện sớm"), fmUser.getEmail());
        AccessDecision decisionDisabled = accessDecisionService.checkEntry(userL1.getId(), internalArea.getId(), now);
        assertFalse(decisionDisabled.allowed());
        assertEquals(AccessSource.NONE, decisionDisabled.source());
    }
}
