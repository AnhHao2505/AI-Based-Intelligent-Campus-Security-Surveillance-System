package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessrequest.*;
import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suite kiểm thử tích hợp toàn diện cho module Access Control và Access Decisions.
 * Bao gồm:
 * - TC-CE-01 .. TC-CE-07: Các trường hợp checkEntry (User Entry Decision)
 * - TC-AA-02, TC-AA-08: Check-in với đơn nhóm bảo lãnh & chế độ sự kiện
 * - TC-AR-03/09/10: Flow đơn cá nhân (tạo -> duyệt -> từ chối -> huỷ)
 * - TC-AR-02: Flow đơn nhóm (tạo -> duyệt FM -> kiểm tra cấp thành viên)
 * - TC-AA-05, TC-AA-06/07: Check-in khách (Guest Entry Decision)
 * - Dropdown khu vực: Phân quyền danh sách khu vực khả dụng để tạo đơn
 */
@Transactional
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
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private String suffix;
    private User normalUserL1;
    private User normalUserL2;
    private User normalUserL3;
    private User adminUser;
    private User fmUser;
    private Area publicArea;
    private Area internalArea;
    private Area contactArea;
    private Area highlyConfidentialArea;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Building b = buildingRepository.findByCodeIgnoreCase("TOA_BETA")
                .orElseGet(() -> buildingRepository.save(Building.builder().code("TOA_BETA").name("Tòa Beta").build()));
        Floor f = floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase("TOA_BETA", "1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(b).floorCode("1").name("Tầng 1").floorOrder(1).build()));

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

        publicArea = areaRepository.save(Area.builder()
                .name("Sảnh công cộng " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(f)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .version(0L)
                .build());

        internalArea = areaRepository.save(Area.builder()
                .name("Phòng học " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(f)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .version(0L)
                .build());

        contactArea = areaRepository.save(Area.builder()
                .name("Phòng Thực hành " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(f)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .version(0L)
                .build());

        highlyConfidentialArea = areaRepository.save(Area.builder()
                .name("Trung tâm Dữ liệu " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(f)
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .version(0L)
                .build());

        entityManager.flush();
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    // =========================================================================
    // TC-CE-01 -> TC-CE-07: User Check-in / Entry Decision
    // =========================================================================

    @Test
    @DisplayName("TC-CE-01: Check-in bằng khuôn mặt thành công (accessLevel >= areaAccessLevel, explicit=false)")
    void tc_ce_01_checkIn_face_success() {
        OffsetDateTime now = OffsetDateTime.now();
        // normalUserL2 (level 2) vào internalArea (level 2, explicit=false)
        AccessDecision decision = accessDecisionService.checkEntry(normalUserL2.getId(), internalArea.getId(), now);

        assertTrue(decision.allowed(), "Check-in phải thành công: " + decision.reason());
        assertEquals(AccessSource.ACCESS_LEVEL, decision.source());
    }

    @Test
    @DisplayName("TC-CE-02: Check-in bằng mã sinh viên (fallback) thành công (qua Assigned Personnel)")
    void tc_ce_02_checkIn_studentCode_fallback_success() {
        OffsetDateTime now = OffsetDateTime.now();
        // Gán normalUserL1 (level 1) vào contactArea (explicit=true) bằng AssignedPersonnel
        AreaAssignedPersonnel aap = AreaAssignedPersonnel.builder()
                .area(contactArea)
                .user(normalUserL1)
                .note("RESEARCHER")
                .validFrom(now.minusDays(1))
                .validTo(now.plusDays(30))
                .createdBy(fmUser)
                .build();
        assignedPersonnelRepository.save(aap);

        AccessDecision decision = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), now);

        assertTrue(decision.allowed(), "Check-in fallback qua Assigned Personnel phải thành công");
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, decision.source());
        assertEquals(aap.getId(), decision.sourceRefId());
    }

    @Test
    @DisplayName("TC-CE-03: Thất bại do access level không đủ (User L1 vào Area L2)")
    void tc_ce_03_checkIn_insufficientAccessLevel_denied() {
        OffsetDateTime now = OffsetDateTime.now();
        // normalUserL1 (level 1) vào internalArea (level 2, explicit=false)
        AccessDecision decision = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);

        assertFalse(decision.allowed(), "Check-in phải thất bại do level không đủ");
        assertEquals(AccessSource.NONE, decision.source());
    }

    @Test
    @DisplayName("TC-CE-04: Thất bại do ngoài khung giờ truy cập của đơn")
    void tc_ce_04_checkIn_outsideRequestWindow_denied() {
        OffsetDateTime now = OffsetDateTime.now();
        // Tạo đơn APPROVED trong khung [now + 2h, now + 4h)
        AccessRequest req = AccessRequest.builder()
                .requestType(RequestType.INDIVIDUAL)
                .area(contactArea)
                .requester(normalUserL2)
                .status(RequestStatus.APPROVED)
                .startTime(now.plusHours(2))
                .endTime(now.plusHours(4))
                .purpose("Nghiên cứu")
                .reviewer(fmUser)
                .reviewedAt(now.minusHours(1))
                .build();
        accessRequestRepository.save(req);

        // Kiểm tra tại thời điểm now (chưa tới khung giờ)
        AccessDecision decision = accessDecisionService.checkEntry(normalUserL2.getId(), contactArea.getId(), now);

        assertFalse(decision.allowed(), "Check-in ngoài khung giờ đơn phải bị từ chối");
        assertEquals(AccessSource.NONE, decision.source());
    }

    @Test
    @DisplayName("TC-CE-05: Thất bại do khu vực yêu cầu đơn (explicit = true) nhưng chưa có đơn APPROVED")
    void tc_ce_05_checkIn_explicitRequired_noApprovedRequest_denied() {
        OffsetDateTime now = OffsetDateTime.now();
        // adminUser (level 4) vào contactArea (explicit=true), không có đơn, không gán personnel
        AccessDecision decision = accessDecisionService.checkEntry(adminUser.getId(), contactArea.getId(), now);

        assertFalse(decision.allowed(), "Khu vực explicit=true phải từ chối nếu không có đơn hoặc gán personnel");
        assertEquals(AccessSource.NONE, decision.source());
    }

    @Test
    @DisplayName("TC-CE-06: Thất bại do tài khoản INACTIVE")
    void tc_ce_06_checkIn_inactiveUser_denied() {
        OffsetDateTime now = OffsetDateTime.now();
        normalUserL2.setIsActive(false);
        userRepository.save(normalUserL2);

        AccessDecision decision = accessDecisionService.checkEntry(normalUserL2.getId(), publicArea.getId(), now);

        assertFalse(decision.allowed(), "Tài khoản INACTIVE phải bị từ chối");
        assertEquals(AccessSource.NONE, decision.source());
        assertTrue(decision.reason().contains("vô hiệu hoá"));
    }

    @Test
    @DisplayName("TC-CE-07: Thất bại do khu vực INACTIVE")
    void tc_ce_07_checkIn_inactiveArea_denied() {
        OffsetDateTime now = OffsetDateTime.now();
        publicArea.setIsActive(false);
        areaRepository.save(publicArea);

        AccessDecision decision = accessDecisionService.checkEntry(normalUserL2.getId(), publicArea.getId(), now);

        assertFalse(decision.allowed(), "Khu vực INACTIVE phải bị từ chối");
        assertEquals(AccessSource.NONE, decision.source());
        assertTrue(decision.reason().contains("ngừng hoạt động"));
    }

    // =========================================================================
    // TC-AA-02, TC-AA-08: Sponsorship & Event Mode Check-in
    // =========================================================================

    @Test
    @DisplayName("TC-AA-02: Check-in khi có đơn nhóm được bảo lãnh thành công")
    void tc_aa_02_checkIn_groupSponsoredRequest_success() {
        OffsetDateTime now = OffsetDateTime.now();

        // normalUserL2 (level 2) tạo đơn nhóm cho contactArea (level 2, explicit=true), bảo lãnh normalUserL1 (level 1)
        AccessRequest req = AccessRequest.builder()
                .requestType(RequestType.GROUP)
                .area(contactArea)
                .requester(normalUserL2)
                .status(RequestStatus.APPROVED)
                .startTime(now.minusHours(1))
                .endTime(now.plusHours(3))
                .purpose("Nhóm thực hành có bảo lãnh")
                .reviewer(fmUser)
                .reviewedAt(now.minusHours(2))
                .members(new ArrayList<>())
                .build();
        req = accessRequestRepository.save(req);

        AccessRequestMember member = AccessRequestMember.builder()
                .accessRequest(req)
                .user(normalUserL1)
                .sponsored(true)
                .build();
        req.getMembers().add(member);
        accessRequestRepository.save(req);

        // Check entry cho thành viên được bảo lãnh normalUserL1
        AccessDecision decision = accessDecisionService.checkEntry(normalUserL1.getId(), contactArea.getId(), now);

        assertTrue(decision.allowed(), "Thành viên được bảo lãnh trong đơn APPROVED phải được cho vào: " + decision.reason());
        assertEquals(AccessSource.ACCESS_REQUEST, decision.source());
        assertEquals(req.getId(), decision.sourceRefId());
    }

    @Test
    @DisplayName("TC-AA-08: Check-in khi đang trong chế độ sự kiện (event mode)")
    void tc_aa_08_checkIn_eventModeActive_success() {
        OffsetDateTime now = OffsetDateTime.now();
        // Bật event mode trên internalArea
        internalArea.setOpenToMembers(true);
        internalArea.setOpenUntil(now.plusHours(2));
        areaRepository.save(internalArea);

        // normalUserL1 (level 1 < area level 2) check-in khi có event mode
        AccessDecision decision = accessDecisionService.checkEntry(normalUserL1.getId(), internalArea.getId(), now);

        assertTrue(decision.allowed(), "Check-in trong khung giờ event mode phải thành công");
        assertEquals(AccessSource.OPEN_EVENT, decision.source());
    }

    // =========================================================================
    // TC-AR-03/09/10: Flow đơn cá nhân (tạo -> duyệt -> từ chối -> huỷ)
    // =========================================================================

    @Test
    @DisplayName("TC-AR-03/09/10: Flow đơn cá nhân: tạo -> duyệt -> từ chối -> huỷ")
    void tc_ar_03_09_10_personalRequestFlow() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = OffsetDateTime.now().plusHours(3);

        // 1. Tạo đơn cá nhân 1 -> Duyệt APPROVED (TC-AR-03)
        IndividualAccessRequestCreateRequest createReq1 = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Làm bài tập lớn"
        );
        AccessRequestResponse resp1 = accessRequestService.createIndividualRequest(createReq1, normalUserL2.getEmail());
        assertNotNull(resp1);
        assertEquals(RequestStatus.PENDING, resp1.status());

        AccessRequestReviewRequest reviewApprove = new AccessRequestReviewRequest(
                RequestStatus.APPROVED,
                null
        );
        AccessRequestResponse reviewed1 = accessRequestService.reviewRequest(resp1.id(), reviewApprove, fmUser.getEmail());
        assertEquals(RequestStatus.APPROVED, reviewed1.status());

        // 2. Tạo đơn cá nhân 2 -> Từ chối REJECTED (TC-AR-09)
        IndividualAccessRequestCreateRequest createReq2 = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                start.plusDays(1),
                end.plusDays(1),
                "Xin vào thêm"
        );
        AccessRequestResponse resp2 = accessRequestService.createIndividualRequest(createReq2, normalUserL2.getEmail());
        AccessRequestReviewRequest reviewReject = new AccessRequestReviewRequest(
                RequestStatus.REJECTED,
                "Khu vực bận vào thời gian này"
        );
        AccessRequestResponse reviewed2 = accessRequestService.reviewRequest(resp2.id(), reviewReject, fmUser.getEmail());
        assertEquals(RequestStatus.REJECTED, reviewed2.status());

        // 3. Tạo đơn cá nhân 3 -> Huỷ CANCELLED bởi người tạo (TC-AR-10)
        IndividualAccessRequestCreateRequest createReq3 = new IndividualAccessRequestCreateRequest(
                contactArea.getId(),
                start.plusDays(2),
                end.plusDays(2),
                "Xin vào buổi khác"
        );
        AccessRequestResponse resp3 = accessRequestService.createIndividualRequest(createReq3, normalUserL2.getEmail());
        AccessRequestResponse cancelled = accessRequestService.cancelRequest(resp3.id(), normalUserL2.getEmail());
        assertEquals(RequestStatus.CANCELLED, cancelled.status());
    }

    // =========================================================================
    // TC-AR-02: Flow đơn nhóm: tạo -> duyệt (FM) -> check cấp thành viên
    // =========================================================================

    @Test
    @DisplayName("TC-AR-02: Flow đơn nhóm: tạo -> duyệt (FM) -> check cấp thành viên và bảo lãnh")
    void tc_ar_02_groupRequestFlow_reviewAndMemberCheck() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = OffsetDateTime.now().plusHours(3);

        // normalUserL2 (level 2) tạo đơn nhóm vào contactArea (level 2, explicit=true)
        // Thành viên: normalUserL3 (level 3 >= 2 -> sponsored=false) và normalUserL1 (level 1 < 2 -> sponsored=true)
        GroupAccessRequestCreateRequest groupCreate = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Nhóm sinh viên thực hành",
                List.of(normalUserL3.getUserCode(), normalUserL1.getUserCode())
        );

        AccessRequestResponse groupResp = accessRequestService.createGroupRequest(groupCreate, normalUserL2.getEmail());
        assertNotNull(groupResp);
        assertEquals(RequestStatus.PENDING, groupResp.status());
        assertEquals(2, groupResp.members().size());

        // FM review đơn
        AccessRequestReviewRequest reviewDTO = new AccessRequestReviewRequest(
                RequestStatus.APPROVED,
                null
        );
        AccessRequestResponse reviewedGroup = accessRequestService.reviewRequest(groupResp.id(), reviewDTO, fmUser.getEmail());
        assertEquals(RequestStatus.APPROVED, reviewedGroup.status());

        // Kiểm tra cờ sponsored trên member response
        boolean hasSponsoredMember = reviewedGroup.members().stream()
                .anyMatch(m -> m.userCode().equals(normalUserL1.getUserCode()) && Boolean.TRUE.equals(m.sponsored()));
        assertTrue(hasSponsoredMember, "Member L1 phải được đánh dấu sponsored = true");
    }

    // =========================================================================
    // TC-AA-05, TC-AA-06/07: Guest Entry Decision
    // =========================================================================

    @Test
    @DisplayName("TC-AA-05: Check-in khách theo khung giờ hẹn thành công")
    void tc_aa_05_guestCheckIn_withinWindow_success() {
        OffsetDateTime now = OffsetDateTime.now();

        // Host là normalUserL2 (level 2), Area là internalArea (level 2, explicit=false)
        GuestVisit visit = GuestVisit.builder()
                .host(normalUserL2)
                .status(GuestVisitStatus.APPROVED)
                .startTime(now.minusHours(1))
                .endTime(now.plusHours(2))
                .areas(Set.of(internalArea))
                .purpose("Gặp đối tác")
                .reviewedBy(fmUser)
                .reviewedAt(now.minusHours(2))
                .build();
        visit = guestVisitRepository.save(visit);

        Guest guest = Guest.builder()
                .visit(visit)
                .fullName("Nguyễn Khách")
                .organization("FPT Software")
                .biometricStatus(GuestBiometricStatus.PHOTO_READY)
                .build();
        guest = guestRepository.save(guest);

        GuestAccessDecision decision = guestAccessDecisionService.checkGuestEntry(guest.getId(), internalArea.getId(), now);

        assertTrue(decision.allowed(), "Khách trong lượt APPROVED và đúng khung giờ phải được cho vào");
        assertEquals("GUEST_VISIT", decision.source());
        assertEquals(visit.getId(), decision.visitId());
    }

    @Test
    @DisplayName("TC-AA-06/07: Khách thất bại do ngoài giờ hoặc lượt chưa duyệt / đã huỷ")
    void tc_aa_06_07_guestCheckIn_outsideWindowOrInactive_denied() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Lượt APPROVED nhưng kiểm tra ngoài khung giờ (TC-AA-06)
        GuestVisit visit1 = GuestVisit.builder()
                .host(normalUserL2)
                .status(GuestVisitStatus.APPROVED)
                .startTime(now.plusHours(2))
                .endTime(now.plusHours(4))
                .areas(Set.of(internalArea))
                .purpose("Gặp chiều nay")
                .reviewedBy(fmUser)
                .reviewedAt(now.minusHours(1))
                .build();
        visit1 = guestVisitRepository.save(visit1);

        Guest guest1 = Guest.builder()
                .visit(visit1)
                .fullName("Khách Ngoài Giờ")
                .organization("Đối tác")
                .biometricStatus(GuestBiometricStatus.PHOTO_READY)
                .build();
        guest1 = guestRepository.save(guest1);

        GuestAccessDecision d1 = guestAccessDecisionService.checkGuestEntry(guest1.getId(), internalArea.getId(), now);
        assertFalse(d1.allowed(), "Khách đến trước giờ phải bị từ chối");
        assertEquals(GuestEntryDenyReason.OUTSIDE_WINDOW, d1.reason());

        // 2. Lượt CANCELLED hoặc PENDING (TC-AA-07)
        GuestVisit visit2 = GuestVisit.builder()
                .host(normalUserL2)
                .status(GuestVisitStatus.CANCELLED)
                .startTime(now.minusHours(1))
                .endTime(now.plusHours(2))
                .areas(Set.of(internalArea))
                .purpose("Lượt đã huỷ")
                .build();
        visit2 = guestVisitRepository.save(visit2);

        Guest guest2 = Guest.builder()
                .visit(visit2)
                .fullName("Khách Lượt Huỷ")
                .organization("Đối tác huỷ")
                .biometricStatus(GuestBiometricStatus.PHOTO_READY)
                .build();
        guest2 = guestRepository.save(guest2);

        GuestAccessDecision d2 = guestAccessDecisionService.checkGuestEntry(guest2.getId(), internalArea.getId(), now);
        assertFalse(d2.allowed(), "Khách có lượt CANCELLED phải bị từ chối");
        assertEquals(GuestEntryDenyReason.VISIT_NOT_ACTIVE, d2.reason());
    }

    // =========================================================================
    // Dropdown khu vực: Verify danh sách trả về đúng quyền của người gọi
    // =========================================================================

    @Test
    @DisplayName("Dropdown khu vực: Verify danh sách trả về đúng quyền của người gọi (NORMAL_USER vs ADMIN/FM)")
    void testAvailableAreasForRequest_DropdownAuthorization() throws Exception {
        // NORMAL_USER L1 (level 1):
        // contactArea có level 2 -> L1 KHÔNG nhìn thấy trong dropdown tạo đơn
        mockMvc.perform(get("/api/areas/available-for-request")
                        .header("Authorization", bearer(normalUserL1))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertFalse(content.contains(contactArea.getName()), "User L1 không được thấy khu vực level 2");
                });

        // NORMAL_USER L2 (level 2):
        // contactArea có level 2 -> L2 nhìn thấy trong dropdown tạo đơn
        // highlyConfidentialArea có level 3 -> L2 KHÔNG nhìn thấy
        mockMvc.perform(get("/api/areas/available-for-request")
                        .header("Authorization", bearer(normalUserL2))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(content.contains(contactArea.getName()), "User L2 phải thấy khu vực level 2: " + content);
                    assertFalse(content.contains(highlyConfidentialArea.getName()), "User L2 không được thấy khu vực level 3");
                });

        // ADMIN (hoặc FM):
        // Nhìn thấy TẤT CẢ các khu vực explicit=true (cả contactArea level 2 và highlyConfidentialArea level 3)
        mockMvc.perform(get("/api/areas/available-for-request")
                        .header("Authorization", bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(content.contains(contactArea.getName()), "Admin phải thấy contactArea");
                    assertTrue(content.contains(highlyConfidentialArea.getName()), "Admin phải thấy highlyConfidentialArea");
                });
    }
}
