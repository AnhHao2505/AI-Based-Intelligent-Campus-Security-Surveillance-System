package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test cho AccessDecisionService#checkEntry.
 * Hai query của repository được giả lập bằng dữ liệu trong bộ nhớ với đúng điều kiện như JPQL
 * (findEffectiveAt, findCoveringRequestsForUser) để kiểm tra ngữ nghĩa khung thời gian nửa mở [from, to).
 */
@ExtendWith(MockitoExtension.class)
class AccessDecisionServiceTest {

    private static final ZoneOffset VN = ZoneOffset.ofHours(7);
    private static final OffsetDateTime DAY = OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, VN);

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Mock
    private AccessRequestRepository accessRequestRepository;

    @InjectMocks
    private AccessDecisionService accessDecisionService;

    private User lecturer;
    private User student;
    private User stranger;
    private Area lab;
    private List<AreaAssignedPersonnel> assignments;
    private List<AccessRequest> requests;

    @BeforeEach
    void setUp() {
        lecturer = user("GV001", "Nguyễn Văn Thầy");
        student = user("SE170001", "Trần Sinh Viên");
        stranger = user("SE170999", "Người Lạ");

        lab = Area.builder()
                .id(UUID.randomUUID())
                .code("LAB-01")
                .name("Phòng Lab 01")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();

        assignments = new ArrayList<>();
        requests = new ArrayList<>();

        for (User u : List.of(lecturer, student, stranger)) {
            lenient().when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        }
        lenient().when(areaRepository.findById(lab.getId())).thenReturn(Optional.of(lab));

        // Giả lập đúng điều kiện JPQL findEffectiveAt
        lenient().when(assignedPersonnelRepository.findEffectiveAt(any(), any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            UUID areaId = inv.getArgument(1);
            OffsetDateTime at = inv.getArgument(2);
            return assignments.stream()
                    .filter(a -> a.getUser().getId().equals(userId) && a.getArea().getId().equals(areaId))
                    .filter(a -> a.getRevokedAt() == null)
                    .filter(a -> !a.getValidFrom().isAfter(at))
                    .filter(a -> a.getValidTo() == null || a.getValidTo().isAfter(at))
                    .toList();
        });

        // Giả lập đúng điều kiện JPQL findCoveringRequestsForUser
        lenient().when(accessRequestRepository.findCoveringRequestsForUser(any(), any(), any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            UUID areaId = inv.getArgument(1);
            OffsetDateTime at = inv.getArgument(2);
            RequestStatus status = inv.getArgument(3);
            return requests.stream()
                    .filter(r -> r.getArea().getId().equals(areaId))
                    .filter(r -> r.getStatus() == status)
                    .filter(r -> !r.getStartTime().isAfter(at) && r.getEndTime().isAfter(at))
                    .filter(r -> r.getRequester().getId().equals(userId)
                            || r.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(userId)))
                    .toList();
        });
    }

    private User user(String code, String name) {
        return User.builder()
                .id(UUID.randomUUID())
                .userCode(code)
                .fullName(name)
                .email(code.toLowerCase() + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();
    }

    private AreaAssignedPersonnel assign(User u, OffsetDateTime from, OffsetDateTime to) {
        AreaAssignedPersonnel a = AreaAssignedPersonnel.builder()
                .id(UUID.randomUUID())
                .area(lab)
                .user(u)
                .validFrom(from)
                .validTo(to)
                .build();
        assignments.add(a);
        return a;
    }

    private AccessRequest request(User requester, RequestStatus status, OffsetDateTime start, OffsetDateTime end, User... members) {
        AccessRequest r = AccessRequest.builder()
                .id(UUID.randomUUID())
                .area(lab)
                .requester(requester)
                .requestType(members.length > 0 ? RequestType.GROUP : RequestType.INDIVIDUAL)
                .purpose("Làm đồ án")
                .startTime(start)
                .endTime(end)
                .status(status)
                .build();
        for (User m : members) {
            r.getMembers().add(AccessRequestMember.builder().id(UUID.randomUUID()).accessRequest(r).user(m).build());
        }
        requests.add(r);
        return r;
    }

    private static OffsetDateTime at(int hour) {
        return DAY.withHour(hour);
    }

    @Test
    @DisplayName("Thầy được gán thường ngày (validTo NULL) vào lab → allowed, ASSIGNED_PERSONNEL")
    void lecturerAssignedUnlimited_Allowed() {
        AreaAssignedPersonnel a = assign(lecturer, DAY.minusDays(60), null);

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9));

        assertTrue(d.allowed());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, d.source());
        assertEquals(a.getId(), d.sourceRefId());
        verify(accessRequestRepository, never()).findCoveringRequestsForUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gán một lần 14h–16h: lúc 15h → allowed; lúc 16h (mốc loại trừ) → denied")
    void oneTimeAssignment_14to16_BoundaryAt16() {
        AreaAssignedPersonnel a = assign(student, at(14), at(16));

        AccessDecision at15 = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(15));
        assertTrue(at15.allowed());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, at15.source());
        assertEquals(a.getId(), at15.sourceRefId());

        AccessDecision at16 = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(16));
        assertFalse(at16.allowed());
        assertEquals(AccessSource.NONE, at16.source());
        assertNull(at16.sourceRefId());
    }

    @Test
    @DisplayName("Gán một lần 14h–16h: đúng 14h (mốc bao gồm) → allowed")
    void oneTimeAssignment_BoundaryAt14_Allowed() {
        assign(student, at(14), at(16));

        assertTrue(accessDecisionService.checkEntry(student.getId(), lab.getId(), at(14)).allowed());
    }

    @Test
    @DisplayName("Bản ghi gán đã thu hồi → denied, NONE")
    void revokedAssignment_Denied() {
        AreaAssignedPersonnel a = assign(lecturer, DAY.minusDays(60), null);
        a.setRevokedAt(DAY.minusDays(1));
        a.setRevokeReason("Nghỉ việc");

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Bản ghi gán chưa tới validFrom → denied")
    void upcomingAssignment_Denied() {
        assign(student, DAY.plusDays(1).withHour(8), null);

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(9));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Đơn APPROVED đúng khung → allowed, ACCESS_REQUEST")
    void approvedRequestInWindow_Allowed() {
        AccessRequest r = request(student, RequestStatus.APPROVED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(10));

        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d.source());
        assertEquals(r.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("Đơn PENDING / REJECTED đúng khung → denied")
    void pendingOrRejectedRequest_Denied() {
        request(student, RequestStatus.PENDING, at(8), at(12));
        request(student, RequestStatus.REJECTED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(10));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Đơn APPROVED nhưng ngoài khung (trước start, đúng end, sau end) → denied")
    void approvedRequestOutOfWindow_Denied() {
        request(student, RequestStatus.APPROVED, at(8), at(12));

        assertFalse(accessDecisionService.checkEntry(student.getId(), lab.getId(), at(7)).allowed());
        assertFalse(accessDecisionService.checkEntry(student.getId(), lab.getId(), at(12)).allowed());
        assertFalse(accessDecisionService.checkEntry(student.getId(), lab.getId(), at(13)).allowed());
    }

    @Test
    @DisplayName("Member của đơn nhóm APPROVED → allowed, ACCESS_REQUEST")
    void memberOfApprovedGroupRequest_Allowed() {
        AccessRequest r = request(lecturer, RequestStatus.APPROVED, at(13), at(17), student);

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(14));

        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d.source());
        assertEquals(r.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("Có cả bản ghi gán và đơn APPROVED → ưu tiên ASSIGNED_PERSONNEL")
    void assignmentTakesPrecedenceOverRequest() {
        AreaAssignedPersonnel a = assign(student, at(8), at(18));
        request(student, RequestStatus.APPROVED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(10));

        assertEquals(AccessSource.ASSIGNED_PERSONNEL, d.source());
        assertEquals(a.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("User inactive dù có bản ghi gán còn hiệu lực → denied, NONE")
    void inactiveUserWithAssignment_Denied() {
        assign(lecturer, DAY.minusDays(60), null);
        lecturer.setIsActive(false);

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        verify(assignedPersonnelRepository, never()).findEffectiveAt(any(), any(), any());
    }

    @Test
    @DisplayName("User đã bị xoá mềm dù có bản ghi gán → denied")
    void softDeletedUser_Denied() {
        assign(lecturer, DAY.minusDays(60), null);
        lecturer.setDeletedAt(DAY.minusDays(1));

        assertFalse(accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9)).allowed());
    }

    @Test
    @DisplayName("User không tồn tại → denied, NONE")
    void unknownUser_Denied() {
        UUID unknown = UUID.randomUUID();
        lenient().when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        AccessDecision d = accessDecisionService.checkEntry(unknown, lab.getId(), at(9));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Area inactive dù user có bản ghi gán → denied, NONE")
    void inactiveArea_Denied() {
        assign(lecturer, DAY.minusDays(60), null);
        lab.setIsActive(false);

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Area đã bị xoá mềm → denied")
    void softDeletedArea_Denied() {
        assign(lecturer, DAY.minusDays(60), null);
        lab.setDeletedAt(DAY.minusDays(1));

        assertFalse(accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(9)).allowed());
    }

    @Test
    @DisplayName("Area không tồn tại → denied")
    void unknownArea_Denied() {
        UUID unknownArea = UUID.randomUUID();
        lenient().when(areaRepository.findById(unknownArea)).thenReturn(Optional.empty());

        assertFalse(accessDecisionService.checkEntry(lecturer.getId(), unknownArea, at(9)).allowed());
    }

    @Test
    @DisplayName("Người không có bản ghi gán hay đơn nào → denied, NONE, sourceRefId null")
    void strangerWithNothing_Denied() {
        assign(lecturer, DAY.minusDays(60), null);
        request(student, RequestStatus.APPROVED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(stranger.getId(), lab.getId(), at(10));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        assertNull(d.sourceRefId());
    }

    @Test
    @DisplayName("Tham số null → IllegalArgumentException")
    void nullArguments_Throw() {
        assertThrows(IllegalArgumentException.class,
                () -> accessDecisionService.checkEntry(null, lab.getId(), at(9)));
        assertThrows(IllegalArgumentException.class,
                () -> accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), null));
    }

    @Test
    @DisplayName("User level 2 vào HIGHLY_CONFIDENTIAL (level 3), không gán/đơn → deny")
    void userLevel2_HighlyConfidentialAreaLevel3_NoAssign_NoRequest_Denied() {
        User staff = user("ST001", "Nhân viên");
        staff.setAccessLevel(2);
        lenient().when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));

        Area serverRoom = Area.builder()
                .id(UUID.randomUUID())
                .code("SRV-01")
                .name("Phòng Server")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(serverRoom.getId())).thenReturn(Optional.of(serverRoom));

        AccessDecision d = accessDecisionService.checkEntry(staff.getId(), serverRoom.getId(), at(10));
        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }



    @Test
    @DisplayName("Hiệu trưởng được gán vào phòng hiệu trưởng (cờ true) → allow ASSIGNED_PERSONNEL")
    void principalAssignedToOffice_FlagTrue_AllowedAssignedPersonnel() {
        User principal = user("HT001", "Hiệu Trưởng");
        principal.setAccessLevel(3);
        lenient().when(userRepository.findById(principal.getId())).thenReturn(Optional.of(principal));

        Area office = Area.builder()
                .id(UUID.randomUUID())
                .code("OFFICE-HT")
                .name("Phòng Hiệu Trưởng")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(office.getId())).thenReturn(Optional.of(office));

        AreaAssignedPersonnel a = AreaAssignedPersonnel.builder()
                .id(UUID.randomUUID())
                .area(office)
                .user(principal)
                .validFrom(DAY.minusDays(30))
                .validTo(null)
                .build();
        assignments.add(a);

        AccessDecision d = accessDecisionService.checkEntry(principal.getId(), office.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, d.source());
        assertEquals(a.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("FM level 2 vào INTERNAL_CONFIDENTIAL (level 2, cờ false) → allow ACCESS_LEVEL")
    void facilityManagerLevel2_SemiPrivateAreaLevel2FlagFalse_AllowedAccessLevel() {
        User fm = user("FM001", "Quản Lý CSVN");
        fm.setRole(Role.FACILITY_MANAGER);
        fm.setAccessLevel(2);
        lenient().when(userRepository.findById(fm.getId())).thenReturn(Optional.of(fm));

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .code("SEMI-01")
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(meetingRoom.getId())).thenReturn(Optional.of(meetingRoom));

        AccessDecision d = accessDecisionService.checkEntry(fm.getId(), meetingRoom.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
        assertNull(d.sourceRefId());
    }

    @Test
    @DisplayName("User level 3 vào CONFIDENTIAL_CONTACT_REQUIRED (level 3, cờ false) → allow ACCESS_LEVEL trực tiếp")
    void userLevel3_ConfidentialContactRequired_AllowedAccessLevel() {
        User lv3User = user("LV3_001", "Cán bộ cấp cao");
        lv3User.setAccessLevel(3);
        lenient().when(userRepository.findById(lv3User.getId())).thenReturn(Optional.of(lv3User));

        Area contactArea = Area.builder()
                .id(UUID.randomUUID())
                .code("CONTACT-01")
                .name("Phòng Yêu Cầu Xác Nhận")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(contactArea.getId())).thenReturn(Optional.of(contactArea));

        AccessDecision d = accessDecisionService.checkEntry(lv3User.getId(), contactArea.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
    }

    @Test
    @DisplayName("User level 2 vào CONFIDENTIAL_CONTACT_REQUIRED (level 3, cờ false) không đơn/gán → deny")
    void userLevel2_ConfidentialContactRequired_NoRequest_Denied() {
        User lv2User = user("LV2_001", "Giảng viên");
        lv2User.setAccessLevel(2);
        lenient().when(userRepository.findById(lv2User.getId())).thenReturn(Optional.of(lv2User));

        Area contactArea = Area.builder()
                .id(UUID.randomUUID())
                .code("CONTACT-01")
                .name("Phòng Yêu Cầu Xác Nhận")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(contactArea.getId())).thenReturn(Optional.of(contactArea));

        AccessDecision d = accessDecisionService.checkEntry(lv2User.getId(), contactArea.getId(), at(10));
        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("User level 1 vào khu vực level 2 cờ false, có đơn APPROVED → allow ACCESS_REQUEST")
    void userLevel1_AreaLevel2FlagFalse_WithApprovedRequest_AllowedAccessRequest() {
        student.setAccessLevel(1);

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .code("SEMI-01")
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(meetingRoom.getId())).thenReturn(Optional.of(meetingRoom));

        AccessRequest req = AccessRequest.builder()
                .id(UUID.randomUUID())
                .area(meetingRoom)
                .requester(student)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Học nhóm")
                .startTime(at(8))
                .endTime(at(12))
                .status(RequestStatus.APPROVED)
                .build();
        requests.add(req);

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), meetingRoom.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d.source());
        assertEquals(req.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("User level 1 vào khu vực level 2 cờ false, không có gì → deny")
    void userLevel1_AreaLevel2FlagFalse_NoAssignNoRequest_Denied() {
        student.setAccessLevel(1);

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .code("SEMI-01")
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(meetingRoom.getId())).thenReturn(Optional.of(meetingRoom));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), meetingRoom.getId(), at(10));
        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("Cờ true, user level 3, có đơn APPROVED → allow ACCESS_REQUEST")
    void flagTrue_UserLevel3_WithApprovedRequest_AllowedAccessRequest() {
        lecturer.setAccessLevel(3);

        AccessRequest req = request(lecturer, RequestStatus.APPROVED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d.source());
        assertEquals(req.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("User level 3 vào HIGHLY_CONFIDENTIAL (cờ true) không có AP và không có đơn → deny NONE")
    void userLevel3_HighlyConfidential_NoAssign_NoRequest_Denied() {
        User lv3User = user("LV3_SERVER", "Chuyên gia Level 3");
        lv3User.setAccessLevel(3);
        lenient().when(userRepository.findById(lv3User.getId())).thenReturn(Optional.of(lv3User));

        Area serverRoom = Area.builder()
                .id(UUID.randomUUID())
                .code("SRV-CRITICAL")
                .name("Phòng Máy Chủ Tối Mật")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(serverRoom.getId())).thenReturn(Optional.of(serverRoom));

        AccessDecision d = accessDecisionService.checkEntry(lv3User.getId(), serverRoom.getId(), at(10));
        assertFalse(d.allowed(), "Cờ explicit == true thì access level không bao giờ cho vào, kể cả level 3");
        assertEquals(AccessSource.NONE, d.source());
    }

    enum AccessScenario {
        HAS_AP,
        APPROVED_IN_WINDOW,
        APPROVED_OUT_OF_WINDOW,
        FINISHED,
        NOTHING
    }

    private static Stream<Arguments> provideAccessDecisionMatrix() {
        List<Arguments> args = new ArrayList<>();
        AreaLevel[] areas = AreaLevel.values();
        int[] userLevels = {1, 2, 3};
        AccessScenario[] scenarios = AccessScenario.values();

        for (AreaLevel areaLevel : areas) {
            int areaAccessLevel;
            boolean explicitRequired;
            switch (areaLevel) {
                case PUBLIC -> { areaAccessLevel = 1; explicitRequired = false; }
                case INTERNAL_CONFIDENTIAL -> { areaAccessLevel = 2; explicitRequired = false; }
                case CONFIDENTIAL_CONTACT_REQUIRED -> { areaAccessLevel = 2; explicitRequired = true; }
                case HIGHLY_CONFIDENTIAL -> { areaAccessLevel = 3; explicitRequired = true; }
                default -> throw new IllegalStateException();
            }

            for (int userLevel : userLevels) {
                for (AccessScenario scenario : scenarios) {
                    boolean expectedAllowed;
                    AccessSource expectedSource;

                    if (scenario == AccessScenario.HAS_AP) {
                        expectedAllowed = true;
                        expectedSource = AccessSource.ASSIGNED_PERSONNEL;
                    } else if (!explicitRequired && userLevel >= areaAccessLevel) {
                        expectedAllowed = true;
                        expectedSource = AccessSource.ACCESS_LEVEL;
                    } else if (scenario == AccessScenario.APPROVED_IN_WINDOW) {
                        expectedAllowed = true;
                        expectedSource = AccessSource.ACCESS_REQUEST;
                    } else {
                        expectedAllowed = false;
                        expectedSource = AccessSource.NONE;
                    }

                    args.add(Arguments.of(areaLevel, userLevel, scenario, expectedAllowed, expectedSource));
                }
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} | Level {1} | Scenario {2} -> Allowed={3}, Source={4}")
    @MethodSource("provideAccessDecisionMatrix")
    @DisplayName("Ma trận 4 loại khu vực × 3 cấp độ user × 5 kịch bản")
    void checkEntry_MatrixTest(
            AreaLevel areaLevel,
            int userLevel,
            AccessScenario scenario,
            boolean expectedAllowed,
            AccessSource expectedSource
    ) {
        assignments.clear();
        requests.clear();

        User testUser = user("USR_MTRX", "Test Matrix User");
        testUser.setAccessLevel(userLevel);
        lenient().when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        int areaAccessLevel;
        boolean explicitAuthRequired;
        switch (areaLevel) {
            case PUBLIC -> { areaAccessLevel = 1; explicitAuthRequired = false; }
            case INTERNAL_CONFIDENTIAL -> { areaAccessLevel = 2; explicitAuthRequired = false; }
            case CONFIDENTIAL_CONTACT_REQUIRED -> { areaAccessLevel = 2; explicitAuthRequired = true; }
            case HIGHLY_CONFIDENTIAL -> { areaAccessLevel = 3; explicitAuthRequired = true; }
            default -> throw new IllegalArgumentException("Unknown level: " + areaLevel);
        }

        Area targetArea = Area.builder()
                .id(UUID.randomUUID())
                .code("AREA-" + areaLevel.name())
                .name("Area " + areaLevel.name())
                .areaLevel(areaLevel)
                .areaAccessLevel(areaAccessLevel)
                .explicitAuthorizationRequired(explicitAuthRequired)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(targetArea.getId())).thenReturn(Optional.of(targetArea));

        OffsetDateTime checkTime = at(10);

        UUID expectedSourceRefId = null;
        switch (scenario) {
            case HAS_AP -> {
                AreaAssignedPersonnel ap = AreaAssignedPersonnel.builder()
                        .id(UUID.randomUUID())
                        .area(targetArea)
                        .user(testUser)
                        .validFrom(at(8))
                        .validTo(at(12))
                        .build();
                assignments.add(ap);
                if (expectedSource == AccessSource.ASSIGNED_PERSONNEL) {
                    expectedSourceRefId = ap.getId();
                }
            }
            case APPROVED_IN_WINDOW -> {
                AccessRequest req = AccessRequest.builder()
                        .id(UUID.randomUUID())
                        .area(targetArea)
                        .requester(testUser)
                        .requestType(RequestType.INDIVIDUAL)
                        .purpose("Đơn hợp lệ trong giờ")
                        .startTime(at(8))
                        .endTime(at(12))
                        .status(RequestStatus.APPROVED)
                        .build();
                requests.add(req);
                if (expectedSource == AccessSource.ACCESS_REQUEST) {
                    expectedSourceRefId = req.getId();
                }
            }
            case APPROVED_OUT_OF_WINDOW -> {
                AccessRequest req = AccessRequest.builder()
                        .id(UUID.randomUUID())
                        .area(targetArea)
                        .requester(testUser)
                        .requestType(RequestType.INDIVIDUAL)
                        .purpose("Đơn ngoài giờ")
                        .startTime(at(14))
                        .endTime(at(18))
                        .status(RequestStatus.APPROVED)
                        .build();
                requests.add(req);
            }
            case FINISHED -> {
                AccessRequest req = AccessRequest.builder()
                        .id(UUID.randomUUID())
                        .area(targetArea)
                        .requester(testUser)
                        .requestType(RequestType.INDIVIDUAL)
                        .purpose("Đơn đã kết thúc")
                        .startTime(at(8))
                        .endTime(at(12))
                        .status(RequestStatus.FINISHED)
                        .build();
                requests.add(req);
            }
            case NOTHING -> {
                // Không có gì
            }
        }

        AccessDecision decision = accessDecisionService.checkEntry(testUser.getId(), targetArea.getId(), checkTime);

        assertEquals(expectedAllowed, decision.allowed(),
                String.format("Sai allowed() cho Area: %s, UserLevel: %d, Scenario: %s", areaLevel, userLevel, scenario));
        assertEquals(expectedSource, decision.source(),
                String.format("Sai source() cho Area: %s, UserLevel: %d, Scenario: %s", areaLevel, userLevel, scenario));
        if (expectedSourceRefId != null) {
            assertEquals(expectedSourceRefId, decision.sourceRefId());
        }
    }
}
