package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessrequest.*;
import com.fa26se040.icss.dto.area.*;
import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suite kiểm thử tích hợp truy vết ma trận Access Control (TC-CE, TC-AA, TC-AR, Dropdown, Preset/Audit).
 */
public class AccessControlSuiteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private GuestAccessDecisionService guestAccessDecisionService;

    @Autowired
    private AccessRequestService accessRequestService;

    @Autowired
    private AreaService areaService;

    @Autowired
    private AreaEventScheduleRepository eventScheduleRepository;

    @Autowired
    private AreaLevelPresetService areaLevelPresetService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private GuestVisitRepository guestVisitRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private CameraRepository cameraRepository;

    @Autowired
    private SecurityIncidentRepository securityIncidentRepository;

    @Autowired
    private AreaLevelPresetRepository areaLevelPresetRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String suffix;
    private User normalUserL1;
    private User normalUserL2;
    private User normalUserL3;
    private User adminUser;
    private User fmUser;
    private User guardUser;
    private Area publicArea;
    private Area internalArea;
    private Area contactArea;
    private Area highlyConfidentialArea;
    private Building testBuilding;
    private Floor testFloor;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        testBuilding = buildingRepository.findByNameIgnoreCase("Tòa Beta")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa Beta").build()));
        testFloor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa Beta", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(testBuilding).name("Tầng 1").floorOrder(1).build()));

        normalUserL1 = userRepository.save(User.builder()
                .userCode("SV1-" + suffix)
                .fullName("Sinh Vien L1 " + suffix)
                .email("sv1-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        normalUserL2 = userRepository.save(User.builder()
                .userCode("SV2-" + suffix)
                .fullName("Sinh Vien L2 " + suffix)
                .email("sv2-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(true)
                .build());

        normalUserL3 = userRepository.save(User.builder()
                .userCode("SV3-" + suffix)
                .fullName("Sinh Vien L3 " + suffix)
                .email("sv3-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build());

        adminUser = userRepository.save(User.builder()
                .userCode("ADM-" + suffix)
                .fullName("Admin " + suffix)
                .email("admin-" + suffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        fmUser = userRepository.save(User.builder()
                .userCode("FM-" + suffix)
                .fullName("FM " + suffix)
                .email("fm-" + suffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        guardUser = userRepository.save(User.builder()
                .userCode("GD-" + suffix)
                .fullName("Guard " + suffix)
                .email("guard-" + suffix + "@fpt.edu.vn")
                .role(Role.GUARD)
                .accessLevel(1)
                .isActive(true)
                .build());

        publicArea = areaRepository.save(Area.builder()
                .name("Sảnh công cộng " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(testFloor)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .isActive(true)
                .version(0L)
                .build());

        internalArea = areaRepository.save(Area.builder()
                .name("Phòng học " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(testFloor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());

        contactArea = areaRepository.save(Area.builder()
                .name("Phòng Thực hành " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(testFloor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());

        highlyConfidentialArea = areaRepository.save(Area.builder()
                .name("Trung tâm Dữ liệu " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(testFloor)
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .isActive(true)
                .version(0L)
                .build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    // =========================================================================
    // TC-CE-01: Public: user active -> ALLOWED; tài khoản bị khoá -> DENIED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-01: Public: user active -> ALLOWED; tài khoản bị khoá -> DENIED")
    void tc_ce_01() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. User active -> ALLOWED (ACCESS_LEVEL)
        AccessDecision d1 = accessDecisionService.checkEntry(normalUserL1.getId(), publicArea.getId(), now);
        assertTrue(d1.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d1.source());

        // 2. User inactive / locked -> DENIED
        normalUserL1.setIsActive(false);
        userRepository.save(normalUserL1);
        AccessDecision d2 = accessDecisionService.checkEntry(normalUserL1.getId(), publicArea.getId(), now);
        assertFalse(d2.allowed());
        assertEquals(AccessSource.NONE, d2.source());
    }

    // =========================================================================
    // TC-CE-02: Nội bộ: L2 -> ALLOWED; L1 không AP -> DENIED; AP hiệu lực -> ALLOWED; AP thu hồi -> DENIED; AP tương lai -> DENIED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-02: Nội bộ: L2 -> ALLOWED; L1 không AP -> DENIED; AP hiệu lực -> ALLOWED; AP thu hồi -> DENIED; AP bắt đầu sau 1h -> DENIED")
    void tc_ce_02() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. L2 -> ALLOWED (ACCESS_LEVEL)
        AccessDecision dL2 = accessDecisionService.checkEntry(normalUserL2.getId(), internalArea.getId(), now);
        assertTrue(dL2.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dL2.source());

        // 2. L1 không AP -> DENIED
        AccessDecision dL1 = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);
        assertFalse(dL1.allowed());
        assertEquals(AccessSource.NONE, dL1.source());

        // 3. AP hiệu lực -> ALLOWED (ASSIGNED_PERSONNEL)
        AreaAssignedPersonnel aap = AreaAssignedPersonnel.builder()
                .area(internalArea)
                .user(normalUserL1)
                .note("RESEARCHER")
                .validFrom(now.minusHours(1))
                .validTo(now.plusHours(4))
                .createdBy(fmUser)
                .build();
        aap = assignedPersonnelRepository.save(aap);

        AccessDecision dAp = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);
        assertTrue(dAp.allowed());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, dAp.source());

        // 4. AP thu hồi (xóa) -> DENIED
        assignedPersonnelRepository.delete(aap);
        AccessDecision dRevoked = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);
        assertFalse(dRevoked.allowed());

        // 5. AP bắt đầu sau 1h -> DENIED
        AreaAssignedPersonnel futureAap = AreaAssignedPersonnel.builder()
                .area(internalArea)
                .user(normalUserL1)
                .note("FUTURE_RESEARCHER")
                .validFrom(now.plusHours(1))
                .validTo(now.plusHours(5))
                .createdBy(fmUser)
                .build();
        assignedPersonnelRepository.save(futureAap);

        AccessDecision dFuture = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);
        assertFalse(dFuture.allowed());
    }

    // =========================================================================
    // TC-CE-03: Liên hệ trước: L3 không AP/đơn -> DENIED; đơn PENDING/REJECTED/CANCELLED -> DENIED; APPROVED [s,e) chứa t -> ALLOWED; t=e và t=s-1m -> DENIED; thành viên bảo lãnh L1 trong khung -> ALLOWED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-03 (V83): Bảo mật (Liên hệ trước cũ): L3, L2 vào thẳng; L1 đơn PENDING/REJECTED/CANCELLED -> DENIED; APPROVED [s,e) chứa t -> ALLOWED; t=e và t=s-1m -> DENIED; thành viên bảo lãnh L1 trong khung -> ALLOWED")
    void tc_ce_03() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        // 1. Cấp ≥ cấp của loại (2) -> vào thẳng, không cần đơn (trước V83 Liên hệ trước chặn cả L3)
        AccessDecision dL3 = accessDecisionService.checkEntry(normalUserL3.getId(), contactArea.getId(), now);
        assertTrue(dL3.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dL3.source());
        AccessDecision dL2 = accessDecisionService.checkEntry(normalUserL2.getId(), contactArea.getId(), now);
        assertTrue(dL2.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dL2.source());
        assertFalse(accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), now).allowed());

        // 2. L1: đơn PENDING/REJECTED/CANCELLED -> DENIED
        AccessRequest req = AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(contactArea)
                .requester(normalUserL1)
                .status(RequestStatus.PENDING)
                .startTime(start)
                .endTime(end)
                .purpose("Lam viec")
                .build();
        req = accessRequestRepository.save(req);
        assertFalse(accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30)).allowed());

        req.setStatus(RequestStatus.REJECTED);
        accessRequestRepository.save(req);
        assertFalse(accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30)).allowed());

        req.setStatus(RequestStatus.CANCELLED);
        accessRequestRepository.save(req);
        assertFalse(accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30)).allowed());

        // 3. APPROVED [s, e) chứa t -> ALLOWED (ACCESS_REQUEST)
        req.setStatus(RequestStatus.APPROVED);
        req.setReviewer(fmUser);
        req.setReviewedAt(now);
        accessRequestRepository.save(req);

        AccessDecision dApproved = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30));
        assertTrue(dApproved.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, dApproved.source());

        // 4. t = e và t = s - 1 phút -> DENIED
        AccessDecision dAtEnd = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), end);
        assertFalse(dAtEnd.allowed());
        AccessDecision dBeforeStart = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.minusMinutes(1));
        assertFalse(dBeforeStart.allowed());

        // 5. Thành viên bảo lãnh L1 (khác người có đơn cá nhân) trong khung giờ -> ALLOWED
        User sponsoredL1 = userRepository.save(User.builder()
                .userCode("SP1-" + suffix)
                .fullName("Sponsored L1 " + suffix)
                .email("sp1-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());
        AccessRequest groupReq = AccessRequest.builder()
                .requestType(RequestType.GROUP)
                .area(contactArea)
                .requester(normalUserL2)
                .status(RequestStatus.APPROVED)
                .startTime(start)
                .endTime(end)
                .purpose("Bao lanh L1")
                .reviewer(fmUser)
                .reviewedAt(now)
                .members(new ArrayList<>())
                .build();
        groupReq = accessRequestRepository.save(groupReq);

        AccessRequestMember memberL1 = AccessRequestMember.builder()
                .accessRequest(groupReq)
                .user(sponsoredL1)
                .sponsored(true)
                .build();
        groupReq.getMembers().add(memberL1);
        accessRequestRepository.save(groupReq);

        assertFalse(accessDecisionService.checkEntry(sponsoredL1.getId(), contactArea.getId(), start.minusMinutes(1)).allowed());
        AccessDecision dSponsored = accessDecisionService.checkEntry(sponsoredL1.getId(), contactArea.getId(), start.plusMinutes(30));
        assertTrue(dSponsored.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, dSponsored.source());
    }

    // =========================================================================
    // TC-CE-04: Tuyệt mật: không AP/đơn -> DENIED; AP -> ALLOWED; đơn cá nhân APPROVED -> ALLOWED; L1 -> DENIED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-04 (V83): Tuyệt mật: L3 vào thẳng; L2 không AP/đơn -> DENIED; AP -> ALLOWED; đơn cá nhân APPROVED -> ALLOWED; L1 -> DENIED")
    void tc_ce_04() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        // 1. L3 = cấp của loại -> vào thẳng (trước V83 cờ chỉ định chặn cả L3)
        AccessDecision dL3 = accessDecisionService.checkEntry(normalUserL3.getId(), highlyConfidentialArea.getId(), now);
        assertTrue(dL3.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, dL3.source());

        // 2. L2 không AP/đơn -> DENIED
        assertFalse(accessDecisionService.checkEntry(normalUserL2.getId(), highlyConfidentialArea.getId(), now).allowed());

        // 3. AP -> ALLOWED
        AreaAssignedPersonnel aap = assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(highlyConfidentialArea)
                .user(normalUserL2)
                .note("SYSADMIN")
                .validFrom(now.minusHours(1))
                .validTo(now.plusHours(4))
                .createdBy(fmUser)
                .build());
        AccessDecision dAp = accessDecisionService.checkEntry(normalUserL2.getId(), highlyConfidentialArea.getId(), now);
        assertTrue(dAp.allowed());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, dAp.source());
        assignedPersonnelRepository.delete(aap);

        // 4. Đơn cá nhân APPROVED -> ALLOWED
        accessRequestRepository.save(AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(highlyConfidentialArea)
                .requester(normalUserL2)
                .status(RequestStatus.APPROVED)
                .startTime(start)
                .endTime(end)
                .purpose("Bao tri he thong")
                .reviewer(fmUser)
                .reviewedAt(now)
                .build());
        AccessDecision dReq = accessDecisionService.checkEntry(normalUserL2.getId(), highlyConfidentialArea.getId(), start.plusMinutes(30));
        assertTrue(dReq.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, dReq.source());

        // 5. L1 không AP/đơn -> DENIED
        assertFalse(accessDecisionService.checkEntry(normalUserL1.getId(), highlyConfidentialArea.getId(), start.plusMinutes(30)).allowed());
    }

    // =========================================================================
    // TC-CE-05: Nhiều nguồn: AP hết hạn + đơn APPROVED -> ALLOWED (REQUEST); AP + đơn -> một quyết định ALLOWED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-05: Nhiều nguồn (L1 vào Bảo mật): AP hết hạn + đơn APPROVED -> ALLOWED (REQUEST); AP + đơn -> một quyết định ALLOWED")
    void tc_ce_05() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        // AP hết hạn (trong quá khứ)
        assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(contactArea)
                .user(normalUserL1)
                .note("EXPIRED")
                .validFrom(now.minusDays(5))
                .validTo(now.minusDays(1))
                .createdBy(fmUser)
                .build());

        // Đơn APPROVED hiệu lực tại start + 30m
        accessRequestRepository.save(AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(contactArea)
                .requester(normalUserL1)
                .status(RequestStatus.APPROVED)
                .startTime(start)
                .endTime(end)
                .purpose("Hop le")
                .reviewer(fmUser)
                .reviewedAt(now)
                .build());

        AccessDecision d1 = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30));
        assertTrue(d1.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d1.source());

        // Thêm AP đang hiệu lực -> vẫn ra quyết định ALLOWED
        assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(contactArea)
                .user(normalUserL1)
                .note("ACTIVE_NOW")
                .validFrom(now.minusHours(1))
                .validTo(now.plusDays(1))
                .createdBy(fmUser)
                .build());

        AccessDecision d2 = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), start.plusMinutes(30));
        assertTrue(d2.allowed());
    }

    // =========================================================================
    // TC-CE-06: Sự kiện: L1 trước giờ kết thúc -> ALLOWED (OPEN_EVENT); tại/sau giờ kết thúc -> DENIED; tài khoản khoá -> DENIED; khách -> DENIED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-06: Sự kiện: L1 trước giờ kết thúc -> ALLOWED (OPEN_EVENT); tại/sau giờ kết thúc -> DENIED; tài khoản khoá -> DENIED; khách -> DENIED")
    void tc_ce_06() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime eventEnd = now.plusHours(2);

        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(eventEnd);
        areaRepository.save(internalArea);

        // 1. L1 trước giờ kết thúc -> ALLOWED (OPEN_EVENT)
        AccessDecision dBefore = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now.plusHours(1));
        assertTrue(dBefore.allowed());
        assertEquals(AccessSource.OPEN_EVENT, dBefore.source());

        // 2. Tại / sau giờ kết thúc -> DENIED (với L1)
        AccessDecision dAt = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), eventEnd);
        assertFalse(dAt.allowed());
        AccessDecision dAfter = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), eventEnd.plusMinutes(1));
        assertFalse(dAfter.allowed());

        // 3. Tài khoản khoá -> DENIED
        normalUserL1.setIsActive(false);
        userRepository.save(normalUserL1);
        AccessDecision dLocked = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now.plusHours(1));
        assertFalse(dLocked.allowed());
        normalUserL1.setIsActive(true);
        userRepository.save(normalUserL1);

        // 4. Khách check-in khu vực sự kiện -> DENIED (khách không có quyền vào bằng OPEN_EVENT)
        GuestVisit visit = guestVisitRepository.save(GuestVisit.builder()
                .host(normalUserL2)
                .status(GuestVisitStatus.APPROVED)
                .startTime(now.minusHours(1))
                .endTime(now.plusHours(3))
                .areas(new HashSet<>(Set.of(publicArea))) // Chỉ đăng ký publicArea, không đăng ký internalArea
                .purpose("Khach su kien FPT")
                .reviewedBy(fmUser)
                .reviewedAt(now.minusHours(2))
                .build());
        Guest guest = guestRepository.save(Guest.builder()
                .visit(visit)
                .fullName("Khach Event")
                .biometricStatus(GuestBiometricStatus.NO_PHOTO)
                .build());

        GuestAccessDecision guestDecision = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now.plusHours(1));
        assertFalse(guestDecision.allowed());
    }

    // =========================================================================
    // TC-CE-07: Khách: trong khung -> ALLOWED; t=e -> DENIED; khu vực ngoài lượt -> DENIED; lượt PENDING/CANCELLED/REVOKED -> DENIED; host mất quyền -> DENIED; đã xoá sinh trắc -> DENIED
    // =========================================================================
    @Test
    @DisplayName("TC-CE-07: Khách: trong khung -> ALLOWED; t=e -> DENIED; khu vực ngoài lượt -> DENIED; lượt PENDING/CANCELLED/REVOKED -> DENIED; host mất quyền -> DENIED; đã xoá sinh trắc -> DENIED")
    void tc_ce_07() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.minusHours(1);
        OffsetDateTime end = now.plusHours(2);

        GuestVisit visit = guestVisitRepository.save(GuestVisit.builder()
                .host(normalUserL2)
                .status(GuestVisitStatus.APPROVED)
                .startTime(start)
                .endTime(end)
                .areas(new HashSet<>(Set.of(internalArea)))
                .purpose("Gap doi tac kinh doanh")
                .reviewedBy(fmUser)
                .reviewedAt(now.minusHours(2))
                .build());

        Guest guest = guestRepository.save(Guest.builder()
                .visit(visit)
                .fullName("Nguyen Van Khach")
                .biometricStatus(GuestBiometricStatus.PHOTO_READY)
                .photoObjectKey("guest-photos/test.jpg")
                .consentConfirmedBy(fmUser)
                .consentConfirmedAt(now.minusHours(2))
                .consentNoticeVersion("v1.0")
                .photoAttachedAt(now.minusHours(2))
                .build());

        // 1. Trong khung -> ALLOWED
        GuestAccessDecision d1 = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now);
        assertTrue(d1.allowed());
        assertEquals("GUEST_VISIT", d1.source());

        // 2. t = e -> DENIED
        GuestAccessDecision dEnd = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), end);
        assertFalse(dEnd.allowed());
        assertEquals(GuestEntryDenyReason.OUTSIDE_WINDOW, dEnd.reason());

        // 3. Khu vực ngoài lượt (contactArea) -> DENIED
        GuestAccessDecision dWrongArea = guestAccessDecisionService.checkGuestEntry(guest.getId(), contactArea.getId(), now);
        assertFalse(dWrongArea.allowed());
        assertEquals(GuestEntryDenyReason.AREA_NOT_IN_VISIT, dWrongArea.reason());

        // 4. Lượt CANCELLED / PENDING / REJECTED -> DENIED
        visit.setStatus(GuestVisitStatus.CANCELLED);
        visit.setCancelledAt(now);
        visit.setCancelReason("Huy don kiem thu");
        guestVisitRepository.save(visit);
        GuestAccessDecision dCancelled = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now);
        assertFalse(dCancelled.allowed());
        assertEquals(GuestEntryDenyReason.VISIT_NOT_ACTIVE, dCancelled.reason());

        visit.setStatus(GuestVisitStatus.APPROVED);
        guestVisitRepository.save(visit);

        // 5. Host mất quyền (host bị vô hiệu hoá) -> DENIED
        normalUserL2.setIsActive(false);
        userRepository.save(normalUserL2);
        GuestAccessDecision dHostInactive = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now);
        assertFalse(dHostInactive.allowed());
        assertEquals(GuestEntryDenyReason.HOST_LOST_ACCESS, dHostInactive.reason());
        normalUserL2.setIsActive(true);
        userRepository.save(normalUserL2);

        // 6. Đã xoá sinh trắc -> DENIED
        guest.setBiometricStatus(GuestBiometricStatus.DELETED);
        guest.setBiometricDeletedAt(now);
        guest.setPhotoObjectKey(null);
        guestRepository.save(guest);
        GuestAccessDecision dBioDeleted = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now);
        assertFalse(dBioDeleted.allowed());
        assertEquals(GuestEntryDenyReason.BIOMETRIC_DELETED, dBioDeleted.reason());
    }

    // =========================================================================
    // TC-AA-02: Ma trận vai trò ADMIN/FM/NORMAL_USER/GUARD/không token: AP, access-rules, event-mode
    // =========================================================================
    @Test
    @DisplayName("TC-AA-02: Ma trận vai trò ADMIN/FM/NORMAL_USER/GUARD/không token: AP list, access-rules (V83: ADMIN 400), event-mode")
    void tc_aa_02() throws Exception {
        // 1. AP: FM -> 200, NORMAL_USER -> 403, GUARD -> 403, không token -> 401
        mockMvc.perform(get("/api/areas/{id}/assigned-personnel", internalArea.getId())
                        .header("Authorization", bearer(fmUser)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/areas/{id}/assigned-personnel", internalArea.getId())
                        .header("Authorization", bearer(normalUserL1)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/areas/{id}/assigned-personnel", internalArea.getId())
                        .header("Authorization", bearer(guardUser)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/areas/{id}/assigned-personnel", internalArea.getId()))
                .andExpect(status().isUnauthorized());

        // 2. access-rules: ADMIN -> 400 ERR_AREA_056 (V83: cấp cố định theo loại), FM -> 403, NORMAL_USER -> 403, không token -> 401
        String rulesJson = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"Kiem tra quyen phan quyen\", \"version\": " + internalArea.getVersion() + "}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId())
                        .header("Authorization", bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AREA_056"));

        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId())
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId())
                        .header("Authorization", bearer(normalUserL1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId()))
                .andExpect(status().isUnauthorized());

        // 3. event-mode: FM -> 200, NORMAL_USER -> 403, GUARD -> 403, không token -> 401
        // X-01a: chỉ còn Dừng (DISABLE) -> mở sự kiện qua lịch + job kích hoạt trước
        OffsetDateTime now = OffsetDateTime.now();
        EventScheduleActivationTestSupport.openViaSchedule(eventScheduleRepository, areaService, internalArea, fmUser,
                "SEMINAR", "Hội thảo/sự kiện chuyên môn", now.minusMinutes(1), now.plusHours(2));
        String eventJson = String.format(
                "{\"action\":\"DISABLE\",\"reasonCode\":\"ENDED_EARLY\",\"note\":\"Kiem tra quyen event dai hon 10 ky tu\",\"version\":%d}",
                areaService.getAreaById(internalArea.getId()).version()
        );

        mockMvc.perform(patch("/api/areas/{id}/event-mode", internalArea.getId())
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/areas/{id}/event-mode", internalArea.getId())
                        .header("Authorization", bearer(normalUserL1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/areas/{id}/event-mode", internalArea.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BR-EV-22 (V83): FM đặt lịch sự kiện: khu Bảo mật -> mở được (qua job kích hoạt); Tuyệt mật / Công khai -> 400 ERR_AREA_022 \"chỉ áp dụng cho khu vực Bảo mật\"")
    void eventMode_onlyConfidentialArea() throws Exception {
        // X-01a: sự kiện chỉ mở qua lịch, BR-EV-22 kiểm ở bước đặt lịch
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime until = now.plusHours(2);
        for (Area area : List.of(highlyConfidentialArea, publicArea)) {
            String json = String.format(
                    "{\"title\":\"Sự kiện thử nghiệm\",\"startAt\":\"%s\",\"endAt\":\"%s\",\"reasonCode\":\"SEMINAR\",\"note\":\"Hoi thao khu vuc khong ho tro su kien\"}",
                    now.plusHours(1), until.plusHours(1));
            mockMvc.perform(post("/api/areas/{id}/event-schedules", area.getId())
                            .header("Authorization", bearer(fmUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ERR_AREA_022"))
                    .andExpect(jsonPath("$.message").value("Chế độ sự kiện chỉ áp dụng cho khu vực Bảo mật"));
        }
        EventScheduleActivationTestSupport.openViaSchedule(eventScheduleRepository, areaService, contactArea, fmUser,
                "SEMINAR", "Hội thảo/sự kiện chuyên môn", now.minusMinutes(1), until);
        assertTrue(accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), OffsetDateTime.now()).allowed(),
                "L1 vào khu Bảo mật đang mở sự kiện");
    }

    // =========================================================================
    // TC-AA-05: Sửa khu vực thiếu version -> ERR_AREA_044; version cũ -> ERR_AREA_045
    // =========================================================================
    @Test
    @DisplayName("TC-AA-05: Sửa khu vực thiếu version -> ERR_AREA_044; version cũ -> ERR_AREA_045")
    void tc_aa_05() {
        // 1. Thiếu version -> ERR_AREA_044
        AreaUpdateRequest reqNoVersion = AreaUpdateRequest.builder()
                .name("Khu vuc sua thieu version")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .floorId(testFloor.getId())
                .version(null)
                .build();
        AreaException ex1 = assertThrows(AreaException.class, () ->
                areaService.update(internalArea.getId(), reqNoVersion, adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_044, ex1.getErrorCode());

        // 2. Version cũ (stale version) -> ERR_AREA_045
        AreaUpdateRequest reqStaleVersion = AreaUpdateRequest.builder()
                .name("Khu vuc sua version cu")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .floorId(testFloor.getId())
                .version(internalArea.getVersion() + 99)
                .build();
        AreaException ex2 = assertThrows(AreaException.class, () ->
                areaService.update(internalArea.getId(), reqStaleVersion, adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_045, ex2.getErrorCode());
    }

    // =========================================================================
    // TC-AA-06: Vô hiệu hoá bị chặn: camera -> ERR_AREA_009; sự cố mở -> 051; sự kiện đang bật -> 052; DELETE -> 405
    // =========================================================================
    @Test
    @DisplayName("TC-AA-06: Vô hiệu hoá bị chặn: camera -> ERR_AREA_009; sự cố mở -> 051; sự kiện đang bật -> 052; DELETE -> 405")
    void tc_aa_06() throws Exception {
        // 1. Còn camera -> ERR_AREA_009
        Camera cam = cameraRepository.save(Camera.builder()
                .name("Cam Block")
                .cameraCode("CAM-BLK-" + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(internalArea)
                .build());
        AreaException exCam = assertThrows(AreaException.class, () ->
                areaService.deactivate(internalArea.getId(), new AreaDeactivateRequest("Ly do vo hieu hoa", internalArea.getVersion()), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_009, exCam.getErrorCode());
        cameraRepository.delete(cam);

        // 2. Sự cố mở -> ERR_AREA_051
        SecurityIncident incident = securityIncidentRepository.save(SecurityIncident.builder()
                .cameraCode("CAM-INC-" + suffix)
                .area(internalArea)
                .building(testBuilding.getName())
                .eventType("INTRUSION")
                .detectedAt(OffsetDateTime.now().minusMinutes(10))
                .status(IncidentStatus.NEW)
                .version(0)
                .build());
        AreaException exInc = assertThrows(AreaException.class, () ->
                areaService.deactivate(internalArea.getId(), new AreaDeactivateRequest("Ly do vo hieu hoa", internalArea.getVersion()), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_051, exInc.getErrorCode());
        securityIncidentRepository.delete(incident);

        // 3. Sự kiện đang bật -> ERR_AREA_052
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(OffsetDateTime.now().plusHours(2));
        areaRepository.save(internalArea);

        AreaException exEvent = assertThrows(AreaException.class, () ->
                areaService.deactivate(internalArea.getId(), new AreaDeactivateRequest("Ly do vo hieu hoa", internalArea.getVersion()), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_052, exEvent.getErrorCode());

        internalArea.setOpenToMembers(false);
        internalArea.setOpenUntil(null);
        areaRepository.save(internalArea);

        // 4. DELETE /api/areas/{id} -> 405 Method Not Allowed
        mockMvc.perform(delete("/api/areas/{id}", internalArea.getId())
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========================================================================
    // TC-AA-07: Vô hiệu hoá thành công thu hồi AP, huỷ đơn PENDING/APPROVED; khôi phục không làm sống lại; vô hiệu hoá lần 2 -> 053; khôi phục khu vực đang active -> 054
    // =========================================================================
    @Test
    @DisplayName("TC-AA-07: Vô hiệu hoá thành công thu hồi AP, huỷ đơn PENDING/APPROVED; khôi phục không làm sống lại; vô hiệu hoá lần 2 -> 053; khôi phục khu vực đang active -> 054")
    void tc_aa_07() {
        OffsetDateTime now = OffsetDateTime.now();

        AccessRequest pendingReq = accessRequestRepository.save(AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(contactArea)
                .requester(normalUserL2)
                .status(RequestStatus.PENDING)
                .startTime(now.plusHours(1))
                .endTime(now.plusHours(3))
                .purpose("Pending req")
                .build());

        AccessRequest approvedReq = accessRequestRepository.save(AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(contactArea)
                .requester(normalUserL3)
                .status(RequestStatus.APPROVED)
                .startTime(now.plusHours(1))
                .endTime(now.plusHours(3))
                .purpose("Approved req")
                .reviewer(fmUser)
                .reviewedAt(now)
                .build());

        // 1. Vô hiệu hoá thành công
        areaService.deactivate(contactArea.getId(), new AreaDeactivateRequest("Vo hieu hoa contactArea", contactArea.getVersion()), adminUser.getEmail());
        Area deactivatedArea = areaRepository.findById(contactArea.getId()).orElseThrow();
        assertFalse(deactivatedArea.getIsActive());
        assertNotNull(deactivatedArea.getDeletedAt());

        // Kiểm tra AP bị thu hồi / đơn bị huỷ
        assertEquals(0, assignedPersonnelRepository.countNotRevokedNotExpired(contactArea.getId(), OffsetDateTime.now()));
        assertEquals(RequestStatus.CANCELLED, accessRequestRepository.findById(pendingReq.getId()).orElseThrow().getStatus());
        assertEquals(RequestStatus.CANCELLED, accessRequestRepository.findById(approvedReq.getId()).orElseThrow().getStatus());

        // 2. Vô hiệu hoá lần 2 -> ERR_AREA_053
        AreaException exDeactTwice = assertThrows(AreaException.class, () ->
                areaService.deactivate(contactArea.getId(), new AreaDeactivateRequest("Vo hieu hoa lan 2", deactivatedArea.getVersion()), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_053, exDeactTwice.getErrorCode());

        // 3. Khôi phục khu vực
        areaService.restore(contactArea.getId(), new AreaRestoreRequest("Khoi phuc contactArea", deactivatedArea.getVersion()), adminUser.getEmail());
        Area restoredArea = areaRepository.findById(contactArea.getId()).orElseThrow();
        assertTrue(restoredArea.getIsActive());
        assertNull(restoredArea.getDeletedAt());

        // Khôi phục không làm sống lại AP hoặc đơn đã huỷ
        assertEquals(0, assignedPersonnelRepository.countNotRevokedNotExpired(contactArea.getId(), OffsetDateTime.now()));
        assertEquals(RequestStatus.CANCELLED, accessRequestRepository.findById(pendingReq.getId()).orElseThrow().getStatus());

        // 4. Khôi phục khu vực đang active -> ERR_AREA_054
        AreaException exRestoreActive = assertThrows(AreaException.class, () ->
                areaService.restore(contactArea.getId(), new AreaRestoreRequest("Khoi phuc lan nua", restoredArea.getVersion()), adminUser.getEmail())
        );
        assertEquals(AreaErrorCode.ERR_AREA_054, exRestoreActive.getErrorCode());
    }

    // =========================================================================
    // TC-AA-08 (V83): FM sửa access-rules / preset / đổi loại -> 403; ADMIN sửa access-rules / preset -> 400 (cấp cố định theo loại);
    // ADMIN đổi loại Bảo mật -> Tuyệt mật -> cấp 3, đổi lại -> cấp 2; cờ chỉ định luôn false
    // =========================================================================
    @Test
    @DisplayName("TC-AA-08 (V83): FM sửa access-rules/preset/đổi loại -> 403; ADMIN sửa access-rules -> ERR_AREA_056, preset -> ERR_AC_005; đổi loại Bảo mật <-> Tuyệt mật: cấp theo loại, cờ luôn false")
    void tc_aa_08() throws Exception {
        // 1. FM sửa access-rules -> 403
        String rulesJson = "{\"areaAccessLevel\": 3, \"explicitAuthorizationRequired\": false, \"reason\": \"FM thu sua rules\", \"version\": " + internalArea.getVersion() + "}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId())
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesJson))
                .andExpect(status().isForbidden());

        // 2. FM sửa preset -> 403
        String presetJson = "{\"areaAccessLevel\": 3, \"explicitAuthorizationRequired\": false, \"reason\": \"FM thu sua preset\", \"version\": 0}";
        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.CONFIDENTIAL)
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetJson))
                .andExpect(status().isForbidden());

        // 3. FM đổi loại -> 403
        String putJson = objectMapper.writeValueAsString(java.util.Map.of(
                "name", internalArea.getName(), "areaLevel", "HIGHLY_CONFIDENTIAL", "floorId", testFloor.getId(),
                "reason", "FM thu doi loai khu vuc", "version", internalArea.getVersion()));
        mockMvc.perform(put("/api/areas/{id}", internalArea.getId())
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putJson))
                .andExpect(status().isForbidden());

        // 4. ADMIN sửa access-rules / preset -> 400, không đổi gì
        mockMvc.perform(patch("/api/areas/{id}/access-rules", internalArea.getId())
                        .header("Authorization", bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AREA_056"));
        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.CONFIDENTIAL)
                        .header("Authorization", bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AC_005"));
        Area unchanged = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertEquals(2, unchanged.getAreaAccessLevel());
        assertEquals(internalArea.getVersion(), unchanged.getVersion());
        assertEquals(2, areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL).orElseThrow().getAreaAccessLevel());

        // 5. ADMIN đổi loại Bảo mật -> Tuyệt mật: cấp 3, cờ false
        areaService.update(internalArea.getId(), AreaUpdateRequest.builder()
                .name(internalArea.getName())
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .floorId(testFloor.getId())
                .version(internalArea.getVersion())
                .reason("Doi sang loai Tuyet mat")
                .build(), adminUser.getEmail());
        Area highly = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, highly.getAreaLevel());
        assertEquals(3, highly.getAreaAccessLevel());
        assertFalse(highly.getExplicitAuthorizationRequired());
        assertEquals(3, ((Number) entityManager.createNativeQuery("SELECT area_access_level FROM areas WHERE id = :id")
                .setParameter("id", internalArea.getId()).getSingleResult()).intValue());

        // 6. Đổi lại Bảo mật: cấp 2
        areaService.update(internalArea.getId(), AreaUpdateRequest.builder()
                .name(internalArea.getName())
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .floorId(testFloor.getId())
                .version(highly.getVersion())
                .reason("Doi lai loai Bao mat")
                .build(), adminUser.getEmail());
        Area back = areaRepository.findById(internalArea.getId()).orElseThrow();
        assertEquals(AreaLevel.CONFIDENTIAL, back.getAreaLevel());
        assertEquals(2, back.getAreaAccessLevel());
        assertEquals(2, ((Number) entityManager.createNativeQuery("SELECT area_access_level FROM areas WHERE id = :id")
                .setParameter("id", internalArea.getId()).getSingleResult()).intValue());
    }

    // =========================================================================
    // TC-AR-02: Đổi ACCESS_REQUEST_MAX_DURATION_HOURS 12->16: đơn 14h qua; về 4: đơn 5h bị từ chối và thông báo chứa "4"; tương tự số ngày
    // =========================================================================
    @Test
    @DisplayName("TC-AR-02: Đổi ACCESS_REQUEST_MAX_DURATION_HOURS 12->16: đơn 14 giờ qua; về 4: đơn 5 giờ bị từ chối và thông báo chứa \"4\"; tương tự số ngày")
    void tc_ar_02() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Đổi max duration hours lên 16
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS.getKey(), "16", "Nang max hours len 16", adminUser.getEmail());
        IndividualAccessRequestCreateRequest req14h = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                now.plusHours(1),
                now.plusHours(15),
                "Don 14 tieng"
        );
        AccessRequestResponse resp14h = accessRequestService.createIndividualRequest(req14h, normalUserL1.getEmail());
        assertNotNull(resp14h);

        // Đổi max duration hours về 4
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS.getKey(), "4", "Ha max hours ve 4", adminUser.getEmail());
        IndividualAccessRequestCreateRequest req5h = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                now.plusHours(1),
                now.plusHours(6),
                "Don 5 tieng"
        );
        IllegalArgumentException exDur = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(req5h, normalUserL1.getEmail())
        );
        assertTrue(exDur.getMessage().contains("4"), "Thông báo lỗi phải chứa giới hạn '4': " + exDur.getMessage());

        // Khôi phục duration về 12
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS.getKey(), "12", "Restore", adminUser.getEmail());

        // 2. Tương tự số ngày advance days: đổi về 5, tạo đơn 7 ngày tới -> từ chối và thông báo chứa "5"
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS.getKey(), "5", "Max advance 5 days", adminUser.getEmail());
        IndividualAccessRequestCreateRequest req7d = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                now.plusDays(7),
                now.plusDays(7).plusHours(2),
                "Don 7 ngay toi"
        );
        IllegalArgumentException exDays = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(req7d, normalUserL1.getEmail())
        );
        assertTrue(exDays.getMessage().contains("5"), "Thông báo lỗi phải chứa giới hạn '5': " + exDays.getMessage());

        // Khôi phục advance days về 30
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS.getKey(), "30", "Restore", adminUser.getEmail());
    }

    // =========================================================================
    // TC-AR-03: Đơn nhóm N+1 thành viên -> từ chối, thông báo chứa N; đúng N -> chấp nhận; thành viên bị khoá -> từ chối
    // =========================================================================
    @Test
    @DisplayName("TC-AR-03: Đơn nhóm N+1 thành viên -> từ chối, thông báo chứa N; đúng N -> chấp nhận; thành viên bị khoá -> từ chối")
    void tc_ar_03() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        // Đặt N = 2 cho dễ kiểm thử
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(), "2", "N=2", adminUser.getEmail());

        // Tạo 3 thành viên khác ngoài người tạo
        User m1 = userRepository.save(User.builder().userCode("M1-" + suffix).fullName("Mem 1").email("m1-" + suffix + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(2).isActive(true).build());
        User m2 = userRepository.save(User.builder().userCode("M2-" + suffix).fullName("Mem 2").email("m2-" + suffix + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(2).isActive(true).build());
        User m3 = userRepository.save(User.builder().userCode("M3-" + suffix).fullName("Mem 3").email("m3-" + suffix + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(2).isActive(true).build());

        // 1. Đơn nhóm N+1 (3 người) -> từ chối, thông báo chứa "2"
        GroupAccessRequestCreateRequest req3 = new GroupAccessRequestCreateRequest(
                contactArea.getId(), start, end, "Nhom 3", List.of(m1.getUserCode(), m2.getUserCode(), m3.getUserCode())
        );
        IllegalArgumentException exN = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createGroupRequest(req3, normalUserL2.getEmail())
        );
        assertTrue(exN.getMessage().contains("2"), "Thông báo phải chứa giới hạn 2: " + exN.getMessage());

        // 2. Đúng N (2 người) -> chấp nhận
        GroupAccessRequestCreateRequest req2 = new GroupAccessRequestCreateRequest(
                contactArea.getId(), start, end, "Nhom 2", List.of(m1.getUserCode(), m2.getUserCode())
        );
        AccessRequestResponse respOk = accessRequestService.createGroupRequest(req2, normalUserL2.getEmail());
        assertNotNull(respOk);
        assertEquals(RequestStatus.PENDING, respOk.status());

        // 3. Thành viên bị khoá -> từ chối
        m1.setIsActive(false);
        userRepository.save(m1);
        GroupAccessRequestCreateRequest reqLocked = new GroupAccessRequestCreateRequest(
                contactArea.getId(), start.plusHours(4), end.plusHours(4), "Nhom co mem locked", List.of(m1.getUserCode(), m2.getUserCode())
        );
        IllegalArgumentException exLocked = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createGroupRequest(reqLocked, normalUserL2.getEmail())
        );
        // Lý do chung như resolve-members, không lộ tài khoản tồn tại nhưng bị khoá (CLAUDE.md 9a)
        assertTrue(exLocked.getMessage().contains("Không tìm thấy người dùng hợp lệ với mã này"), exLocked.getMessage());
        assertFalse(exLocked.getMessage().contains("vô hiệu"), exLocked.getMessage());

        // Khôi phục N = 30
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(), "30", "Restore", adminUser.getEmail());
    }

    // =========================================================================
    // TC-AR-09: Liên hệ trước: L2 tạo nhóm có 2 L1 -> chấp nhận, sponsored=true cho cả hai; FM duyệt OK; người tạo bị hạ cấp trước khi duyệt -> duyệt bị từ chối
    // =========================================================================
    @Test
    @DisplayName("TC-AR-09 (V83): Bảo mật: L2 tạo nhóm có 2 L1 -> chấp nhận, sponsored=true cho cả hai; FM duyệt OK; người tạo bị hạ cấp trước khi duyệt -> FM vẫn duyệt được (BR-RQ-02 không kiểm lại cấp người tạo)")
    void tc_ar_09() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        User extraL1 = userRepository.save(User.builder()
                .userCode("EX1-" + suffix)
                .fullName("Extra L1 " + suffix)
                .email("ex1-" + suffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        // 1. L2 tạo nhóm có 2 L1 vào Liên hệ trước -> Chấp nhận, sponsored = true cho cả hai
        GroupAccessRequestCreateRequest groupReq = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "L2 bao lanh 2 L1",
                List.of(normalUserL1.getUserCode(), extraL1.getUserCode())
        );
        AccessRequestResponse resp = accessRequestService.createGroupRequest(groupReq, normalUserL2.getEmail());
        assertNotNull(resp);
        assertEquals(RequestStatus.PENDING, resp.status());
        assertEquals(2, resp.members().size());
        assertTrue(resp.members().stream().allMatch(m -> Boolean.TRUE.equals(m.sponsored())), "Cả 2 L1 phải được sponsored=true");

        // 2. FM duyệt OK
        AccessRequestReviewRequest approveDTO = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        AccessRequestResponse approvedResp = accessRequestService.reviewRequest(resp.id(), approveDTO, fmUser.getEmail());
        assertEquals(RequestStatus.APPROVED, approvedResp.status());

        // 3. Tạo đơn nhóm khác, nhưng hạ cấp người tạo (L2 -> L1) trước khi duyệt -> FM duyệt bị từ chối
        GroupAccessRequestCreateRequest groupReq2 = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start.plusHours(4),
                end.plusHours(4),
                "L2 sap bi ha cap",
                List.of(normalUserL1.getUserCode())
        );
        AccessRequestResponse resp2 = accessRequestService.createGroupRequest(groupReq2, normalUserL2.getEmail());

        // Hạ cấp normalUserL2 về Level 1
        normalUserL2.setAccessLevel(1);
        userRepository.save(normalUserL2);

        AccessRequestResponse approvedLowered = accessRequestService.reviewRequest(resp2.id(), approveDTO, fmUser.getEmail());
        assertEquals(RequestStatus.APPROVED, approvedLowered.status());
    }

    // =========================================================================
    // TC-AR-10: Nội bộ/Tuyệt mật: nhóm có L1 -> từ chối; L1 tạo đơn cá nhân -> từ chối; key cấu hình: giá trị lạ/rỗng/chứa PUBLIC hoặc HIGHLY -> từ chối; thêm INTERNAL hợp lệ -> có hiệu lực ngay
    // =========================================================================
    @Test
    @DisplayName("TC-AR-10 (V83): Bảo mật: nhóm có L1 -> nhận, sponsored; Tuyệt mật: nhóm -> từ chối; đơn cá nhân L1 vào Bảo mật -> nhận, L1 vào Tuyệt mật / L2 vào Bảo mật -> từ chối; key bảo lãnh: giá trị lạ/rỗng/PUBLIC/HIGHLY/tên loại cũ -> từ chối, chỉ còn CONFIDENTIAL")
    void tc_ar_10() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(1);
        OffsetDateTime end = now.plusHours(3);

        // 1. V83: Nội bộ gộp vào Bảo mật, Bảo mật cho bảo lãnh -> nhóm có L1 được nhận, L1 sponsored
        GroupAccessRequestCreateRequest groupInternal = new GroupAccessRequestCreateRequest(
                internalArea.getId(), start, end, "Nhom vao Bao mat", List.of(normalUserL1.getUserCode())
        );
        AccessRequestResponse groupResp = accessRequestService.createGroupRequest(groupInternal, normalUserL2.getEmail());
        assertEquals(RequestStatus.PENDING, groupResp.status());
        assertTrue(groupResp.members().get(0).sponsored());

        // 2. Nhóm có L1 vào Tuyệt mật (HIGHLY_CONFIDENTIAL) -> Từ chối
        GroupAccessRequestCreateRequest groupHighly = new GroupAccessRequestCreateRequest(
                highlyConfidentialArea.getId(), start, end, "Nhom vao Highly", List.of(normalUserL1.getUserCode())
        );
        assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createGroupRequest(groupHighly, normalUserL3.getEmail())
        );

        // 3. V83 (BR-RQ-01): L1 tạo đơn cá nhân vào Bảo mật -> nhận; L1 vào Tuyệt mật -> 400 vượt khoảng cách cấp;
        //    L2 vào Bảo mật -> 400 đã đủ cấp
        IndividualAccessRequestCreateRequest indInternal = new IndividualAccessRequestCreateRequest(
                internalArea.getId(), start.plusHours(4), end.plusHours(4), "L1 ca nhan vao Bao mat"
        );
        assertEquals(RequestStatus.PENDING, accessRequestService.createIndividualRequest(indInternal, normalUserL1.getEmail()).status());
        IllegalArgumentException exGap = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(new IndividualAccessRequestCreateRequest(
                        highlyConfidentialArea.getId(), start.plusHours(8), end.plusHours(8), "L1 ca nhan vao Tuyet mat"), normalUserL1.getEmail())
        );
        assertEquals("Chỉ được xin vào khu vực cao hơn cấp của bạn tối đa 1 cấp (khu vực cấp 3, bạn cấp 1)", exGap.getMessage());
        IllegalArgumentException exEnough = assertThrows(IllegalArgumentException.class, () ->
                accessRequestService.createIndividualRequest(new IndividualAccessRequestCreateRequest(
                        internalArea.getId(), start.plusHours(8), end.plusHours(8), "L2 ca nhan vao Bao mat"), normalUserL2.getEmail())
        );
        assertEquals("Bạn đã đủ cấp vào khu vực này, không cần tạo yêu cầu truy cập (khu vực cấp 2, bạn cấp 2)", exEnough.getMessage());

        // 4. Validate key cấu hình ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES
        // Giá trị lạ
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey(), "UNKNOWN_TYPE", "Gia tri la", adminUser.getEmail())
        );
        // Rỗng
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey(), "", "Rong", adminUser.getEmail())
        );
        // Chứa PUBLIC
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey(), "PUBLIC,CONFIDENTIAL", "Chua PUBLIC", adminUser.getEmail())
        );
        // Chứa HIGHLY_CONFIDENTIAL
        assertThrows(IllegalArgumentException.class, () ->
                systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey(), "HIGHLY_CONFIDENTIAL", "Chua HIGHLY", adminUser.getEmail())
        );

        // Tên loại cũ (V83 đã gộp) -> từ chối
        for (String retired : List.of("INTERNAL_CONFIDENTIAL", "CONFIDENTIAL_CONTACT_REQUIRED")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey(), retired, "Ten loai cu", adminUser.getEmail())
            );
            assertEquals("Loại khu vực không hợp lệ: " + retired, ex.getMessage());
        }

        // 5. Giá trị hợp lệ duy nhất sau gộp: CONFIDENTIAL
        assertEquals(java.util.Set.of(AreaLevel.CONFIDENTIAL), systemConfigService.getSponsorAllowedAreaLevels());
    }

    // =========================================================================
    // Dropdown: Khu vực cờ tắt, vô hiệu hoá, cấp cao hơn người gửi không xuất hiện; L1 không thấy khu vực Nội bộ nào
    // =========================================================================
    @Test
    @DisplayName("Dropdown (V83): ?type=INDIVIDUAL chỉ khu cao hơn cấp người gửi trong khoảng cách cấp; ?type=GROUP chỉ khu người gửi tự vào được (Tuyệt mật không nhận đơn nhóm); khu vô hiệu hoá không xuất hiện")
    void testDropdown_AvailableAreasFiltering() throws Exception {
        // 1. Đơn cá nhân: L1 thấy hai khu Bảo mật, không thấy Tuyệt mật (cách 2 cấp); L2 chỉ thấy Tuyệt mật; L3 không thấy khu nào
        String l1Individual = dropdown(normalUserL1, "INDIVIDUAL");
        assertTrue(l1Individual.contains(contactArea.getName()) && l1Individual.contains(internalArea.getName()), "L1 thấy khu Bảo mật");
        assertFalse(l1Individual.contains(highlyConfidentialArea.getName()), "L1 không thấy Tuyệt mật");
        String l2Individual = dropdown(normalUserL2, "INDIVIDUAL");
        assertTrue(l2Individual.contains(highlyConfidentialArea.getName()), "L2 thấy Tuyệt mật");
        assertFalse(l2Individual.contains(contactArea.getName()) || l2Individual.contains(internalArea.getName()), "L2 đã vào thẳng Bảo mật");
        String l3Individual = dropdown(normalUserL3, "INDIVIDUAL");
        assertFalse(l3Individual.contains(contactArea.getName()) || l3Individual.contains(highlyConfidentialArea.getName()), "L3 vào thẳng mọi khu");
        assertFalse(l1Individual.contains(publicArea.getName()), "Công khai không cần đơn");

        // 2. Đơn nhóm: L2 thấy khu Bảo mật, không thấy Tuyệt mật; L1 không thấy khu nào
        String l2Group = dropdown(normalUserL2, "GROUP");
        assertTrue(l2Group.contains(contactArea.getName()) && l2Group.contains(internalArea.getName()));
        assertFalse(l2Group.contains(highlyConfidentialArea.getName()), "Tuyệt mật không nhận đơn nhóm (mặc định)");
        String l1Group = dropdown(normalUserL1, "GROUP");
        assertFalse(l1Group.contains(contactArea.getName()) || l1Group.contains(internalArea.getName()), "L1 không tự vào được khu Bảo mật");

        // 3. Không gửi type (client cũ): hợp của hai danh sách, kèm cờ từng loại
        String l2All = dropdown(normalUserL2, null);
        assertTrue(l2All.contains(contactArea.getName()) && l2All.contains(highlyConfidentialArea.getName()));

        // 4. Vô hiệu hoá contactArea -> không còn xuất hiện
        contactArea.setIsActive(false);
        areaRepository.save(contactArea);
        assertFalse(dropdown(normalUserL1, "INDIVIDUAL").contains(contactArea.getName()), "Khu vực vô hiệu hoá không được xuất hiện");
        assertFalse(dropdown(normalUserL2, "GROUP").contains(contactArea.getName()), "Khu vực vô hiệu hoá không được xuất hiện");
    }

    private String dropdown(User caller, String type) throws Exception {
        var req = get("/api/areas/available-for-request").header("Authorization", bearer(caller));
        if (type != null) {
            req = req.param("type", type);
        }
        MvcResult r = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Preset/Audit: FM sửa preset -> 403; ADMIN sửa cấp preset -> 200 + audit; audit-logs: ADMIN, FM -> 200; NORMAL_USER, GUARD -> 403
    // =========================================================================
    @Test
    @DisplayName("Preset/Audit: FM sửa preset -> 403; ADMIN sửa cấp preset -> 400 ERR_AC_005, không audit; audit-logs: ADMIN, FM -> 200; NORMAL_USER, GUARD -> 403")
    void testPresetAndAuditAuthorization() throws Exception {
        // 1. FM sửa preset -> 403
        var currentPreset = areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL).orElseThrow();
        String presetJson = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"FM thu sua preset\", \"version\": " + currentPreset.getVersion() + "}";
        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.CONFIDENTIAL)
                        .header("Authorization", bearer(fmUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetJson))
                .andExpect(status().isForbidden());

        // 2. ADMIN sửa cấp preset -> 400 ERR_AC_005 (V83: bảng chỉ để xem), không đổi cấp, không audit
        String adminPresetJson = "{\"areaAccessLevel\": 3, \"explicitAuthorizationRequired\": false, \"reason\": \"ADMIN sua preset " + suffix + "\", \"version\": " + currentPreset.getVersion() + "}";
        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.CONFIDENTIAL)
                        .header("Authorization", bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminPresetJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AC_005"));
        assertEquals(2, areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL).orElseThrow().getAreaAccessLevel());
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM audit_logs WHERE target_type = 'LEVEL_PRESET' AND reason = :reason"
        ).setParameter("reason", "ADMIN sua preset " + suffix).getSingleResult();
        assertEquals(0, count.intValue());

        // 3. audit-logs: ADMIN, FM -> 200; NORMAL_USER, GUARD -> 403
        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", bearer(fmUser)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", bearer(normalUserL1)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", bearer(guardUser)))
                .andExpect(status().isForbidden());
    }
}
