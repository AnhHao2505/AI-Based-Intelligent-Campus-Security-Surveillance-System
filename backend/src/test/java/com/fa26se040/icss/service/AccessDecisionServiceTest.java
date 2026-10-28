package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardIncidentAccessRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.stream.Stream;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private CameraRepository cameraRepository;

    @Mock
    private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Mock
    private AccessRequestRepository accessRequestRepository;

    @Mock
    private com.fa26se040.icss.repository.AreaEventScheduleRepository areaEventScheduleRepository;

    @Mock
    private com.fa26se040.icss.repository.AreaEventSessionRepository areaEventSessionRepository;

    @Mock
    private com.fa26se040.icss.repository.GuestRepository guestRepository;

    @Mock
    private com.fa26se040.icss.service.GuestAccessDecisionService guestAccessDecisionService;

    @Mock
    private CameraAccessLogService cameraAccessLogService;

    @Mock
    private GuardIncidentAccessRepository guardIncidentAccessRepository;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AccessDecisionService accessDecisionService;

    private User lecturer;
    private User student;
    private User stranger;
    private Area lab;
    private List<AreaAssignedPersonnel> assignments;
    private List<AccessRequest> requests;
    private List<SecurityIncident> incidents;

    @BeforeEach
    void setUp() {
        lecturer = user("GV001", "Nguyễn Văn Thầy");
        student = user("SE170001", "Trần Sinh Viên");
        stranger = user("SE170999", "Người Lạ");

        lab = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Lab 01")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();

        assignments = new ArrayList<>();
        requests = new ArrayList<>();
        incidents = new ArrayList<>();

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

        // Giả lập đúng điều kiện JPQL findLatestClaimedByUserInArea (mới nhất theo claimedAt, giới hạn theo Pageable)
        lenient().when(guardIncidentAccessRepository.findLatestClaimedByUserInArea(any(), any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            UUID areaId = inv.getArgument(1);
            Pageable pageable = inv.getArgument(2);
            return incidents.stream()
                    .filter(i -> i.getArea().getId().equals(areaId))
                    .filter(i -> i.getClaimedBy() != null && i.getClaimedBy().getId().equals(userId))
                    .filter(i -> i.getStatus() != IncidentStatus.NEW)
                    .filter(i -> i.getClaimedAt() != null)
                    .sorted(Comparator.comparing(SecurityIncident::getClaimedAt).reversed())
                    .limit(pageable.getPageSize())
                    .toList();
        });
        lenient().when(systemConfigService.getInt(ConfigKey.GUARD_INCIDENT_ACCESS_MINUTES)).thenReturn(INCIDENT_ACCESS_MINUTES);
        lenient().when(systemConfigService.getInt(ConfigKey.GUARD_INCIDENT_EXIT_GRACE_MINUTES)).thenReturn(INCIDENT_EXIT_GRACE_MINUTES);
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
                .name("Phòng Server")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(serverRoom.getId())).thenReturn(Optional.of(serverRoom));

        AccessDecision d = accessDecisionService.checkEntry(staff.getId(), serverRoom.getId(), at(10));
        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }



    @Test
    @DisplayName("Hiệu trưởng cấp 3 được gán vào phòng Tuyệt mật → ASSIGNED_PERSONNEL đứng trước ACCESS_LEVEL")
    void principalAssignedToOffice_AllowedAssignedPersonnel() {
        User principal = user("HT001", "Hiệu Trưởng");
        principal.setAccessLevel(3);
        lenient().when(userRepository.findById(principal.getId())).thenReturn(Optional.of(principal));

        Area office = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Hiệu Trưởng")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
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
    @DisplayName("FM cấp 2 vào khu Bảo mật (cấp 2) → allow ACCESS_LEVEL")
    void facilityManagerLevel2_ConfidentialArea_AllowedAccessLevel() {
        User fm = user("FM001", "Quản Lý CSVN");
        fm.setRole(Role.FACILITY_MANAGER);
        fm.setAccessLevel(2);
        lenient().when(userRepository.findById(fm.getId())).thenReturn(Optional.of(fm));

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(meetingRoom.getId())).thenReturn(Optional.of(meetingRoom));

        AccessDecision d = accessDecisionService.checkEntry(fm.getId(), meetingRoom.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
        assertNull(d.sourceRefId());
    }

    @Test
    @DisplayName("User cấp 3 vào khu Bảo mật (cấp 2) → allow ACCESS_LEVEL: cấp cao hơn vào thẳng")
    void userLevel3_ConfidentialArea_AllowedAccessLevel() {
        User lv3User = user("LV3_001", "Cán bộ cấp cao");
        lv3User.setAccessLevel(3);
        lenient().when(userRepository.findById(lv3User.getId())).thenReturn(Optional.of(lv3User));

        Area contactArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Yêu Cầu Xác Nhận")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(contactArea.getId())).thenReturn(Optional.of(contactArea));

        AccessDecision d = accessDecisionService.checkEntry(lv3User.getId(), contactArea.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
    }

    @Test
    @DisplayName("User cấp 2 vào khu Bảo mật (Liên hệ trước cũ) không đơn / gán → allow ACCESS_LEVEL (V83 bỏ cờ chỉ định)")
    void userLevel2_ConfidentialArea_NoRequest_AllowedAccessLevel() {
        User lv2User = user("LV2_001", "Giảng viên");
        lv2User.setAccessLevel(2);
        lenient().when(userRepository.findById(lv2User.getId())).thenReturn(Optional.of(lv2User));

        Area contactArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Yêu Cầu Xác Nhận")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(contactArea.getId())).thenReturn(Optional.of(contactArea));

        AccessDecision d = accessDecisionService.checkEntry(lv2User.getId(), contactArea.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
    }

    @Test
    @DisplayName("User cấp 1 vào khu Bảo mật, có đơn APPROVED → allow ACCESS_REQUEST")
    void userLevel1_ConfidentialArea_WithApprovedRequest_AllowedAccessRequest() {
        student.setAccessLevel(1);

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.CONFIDENTIAL)
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
    @DisplayName("User cấp 1 vào khu Bảo mật, không có gì → deny, lý do thiếu cấp")
    void userLevel1_ConfidentialArea_NoAssignNoRequest_Denied() {
        student.setAccessLevel(1);

        Area meetingRoom = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Họp Chung")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(meetingRoom.getId())).thenReturn(Optional.of(meetingRoom));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), meetingRoom.getId(), at(10));
        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        assertEquals("Cấp độ truy cập không đủ (Khu vực Cấp 2, người dùng Cấp 1)", d.reason());
    }

    @Test
    @DisplayName("User cấp 2 vào khu Tuyệt mật, có đơn APPROVED → allow ACCESS_REQUEST")
    void userLevel2_HighlyConfidential_WithApprovedRequest_AllowedAccessRequest() {
        lecturer.setAccessLevel(2);

        AccessRequest req = request(lecturer, RequestStatus.APPROVED, at(8), at(12));

        AccessDecision d = accessDecisionService.checkEntry(lecturer.getId(), lab.getId(), at(10));
        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_REQUEST, d.source());
        assertEquals(req.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("User cấp 3 vào khu Tuyệt mật, không AP / đơn → allow ACCESS_LEVEL (V83 bỏ cờ chỉ định)")
    void userLevel3_HighlyConfidential_NoAssignNoRequest_AllowedAccessLevel() {
        User lv3User = user("LV3_SERVER", "Chuyên gia Level 3");
        lv3User.setAccessLevel(3);
        lenient().when(userRepository.findById(lv3User.getId())).thenReturn(Optional.of(lv3User));

        Area serverRoom = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Máy Chủ Tối Mật")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(serverRoom.getId())).thenReturn(Optional.of(serverRoom));

        AccessDecision d = accessDecisionService.checkEntry(lv3User.getId(), serverRoom.getId(), at(10));
        assertTrue(d.allowed(), "Cấp người ≥ cấp của loại thì vào thẳng, kể cả Tuyệt mật");
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
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
        int[] userLevels = {1, 2, 3};
        for (AreaLevel areaLevel : AreaLevel.values()) {
            for (int userLevel : userLevels) {
                for (AccessScenario scenario : AccessScenario.values()) {
                    boolean expectedAllowed;
                    AccessSource expectedSource;

                    if (scenario == AccessScenario.HAS_AP) {
                        expectedAllowed = true;
                        expectedSource = AccessSource.ASSIGNED_PERSONNEL;
                    } else if (userLevel >= areaLevel.level()) {
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
    @DisplayName("Ma trận 3 loại khu vực × 3 cấp độ user × 5 kịch bản")
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

        Area targetArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Area " + areaLevel.name())
                .areaLevel(areaLevel)
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

    @ParameterizedTest(name = "{0} | cấp {1} -> vào thẳng = {2}")
    @CsvSource({
            "PUBLIC, 1, true", "PUBLIC, 2, true", "PUBLIC, 3, true",
            "CONFIDENTIAL, 1, false", "CONFIDENTIAL, 2, true", "CONFIDENTIAL, 3, true",
            "HIGHLY_CONFIDENTIAL, 1, false", "HIGHLY_CONFIDENTIAL, 2, false", "HIGHLY_CONFIDENTIAL, 3, true"
    })
    @DisplayName("BR-AC-24 (V83): ma trận vào thẳng 3×3, không AP / đơn / sự kiện / sự cố")
    void checkEntry_DirectEntryMatrix3x3(AreaLevel areaLevel, int userLevel, boolean expectedAllowed) {
        User u = user("USR_3X3", "Người dùng 3x3");
        u.setAccessLevel(userLevel);
        lenient().when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        Area target = area("Khu " + areaLevel.label(), areaLevel);

        AccessDecision d = accessDecisionService.checkEntry(u.getId(), target.getId(), at(10));

        assertEquals(expectedAllowed, d.allowed(), areaLevel + " / cấp " + userLevel);
        assertEquals(expectedAllowed ? AccessSource.ACCESS_LEVEL : AccessSource.NONE, d.source());
        if (!expectedAllowed) {
            assertEquals("Cấp độ truy cập không đủ (Khu vực Cấp " + areaLevel.level() + ", người dùng Cấp " + userLevel + ")",
                    d.reason());
        }
    }

    @Test
    @DisplayName("BR-AC-25 / BR-ES-10 (V83): chế độ sự kiện chỉ mở khu Bảo mật; khu Tuyệt mật có cờ sự kiện vẫn deny")
    void eventMode_OnlyConfidentialArea() {
        student.setAccessLevel(1);
        Area confidential = area("Phòng Lab Sự Kiện", AreaLevel.CONFIDENTIAL);
        confidential.setOpenToMembers(true);
        confidential.setOpenUntil(at(12));
        Area highly = area("Phòng Máy Chủ Sự Kiện", AreaLevel.HIGHLY_CONFIDENTIAL);
        highly.setOpenToMembers(true);
        highly.setOpenUntil(at(12));
        // E-5: OPEN_EVENT theo phiên sự kiện phủ thời điểm vào, không theo cờ trên areas
        when(areaEventSessionRepository.existsSessionCoveringAt(confidential.getId(), at(10))).thenReturn(true);

        AccessDecision inConfidential = accessDecisionService.checkEntry(student.getId(), confidential.getId(), at(10));
        assertTrue(inConfidential.allowed());
        assertEquals(AccessSource.OPEN_EVENT, inConfidential.source());

        AccessDecision inHighly = accessDecisionService.checkEntry(student.getId(), highly.getId(), at(10));
        assertFalse(inHighly.allowed());
        assertEquals(AccessSource.NONE, inHighly.source());
    }

    @Test
    @DisplayName("checkCameraEntry: mã camera hoặc userCode trống -> DENIED")
    void checkCameraEntry_BlankInputs() {
        var res1 = accessDecisionService.checkCameraEntry("", "GV001", at(9));
        assertFalse(res1.isAllowed());
        assertTrue(res1.getReason().contains("Mã camera không được để trống"));

        var res2 = accessDecisionService.checkCameraEntry("CAM-01", "", at(9));
        assertFalse(res2.isAllowed());
        assertTrue(res2.getReason().contains("Mã người dùng không được để trống"));
    }

    @Test
    @DisplayName("checkCameraEntry: Camera không tồn tại -> DENIED")
    void checkCameraEntry_CameraNotFound() {
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-999")).thenReturn(Optional.empty());

        var res = accessDecisionService.checkCameraEntry("CAM-999", "GV001", at(9));
        assertFalse(res.isAllowed());
        assertTrue(res.getReason().contains("Camera không tồn tại"));
    }

    @Test
    @DisplayName("checkCameraEntry: Camera chưa gán khu vực -> DENIED")
    void checkCameraEntry_CameraWithoutArea() {
        Camera cam = Camera.builder().cameraCode("CAM-01").area(null).build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));

        var res = accessDecisionService.checkCameraEntry("CAM-01", "GV001", at(9));
        assertFalse(res.isAllowed());
        assertTrue(res.getReason().contains("Camera chưa được gán"));
    }

    @Test
    @DisplayName("checkCameraEntry: Người dùng không tồn tại -> DENIED")
    void checkCameraEntry_UserNotFound() {
        Camera cam = Camera.builder().cameraCode("CAM-01").area(lab).build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE99999")).thenReturn(Optional.empty());

        var res = accessDecisionService.checkCameraEntry("CAM-01", "SE99999", at(9));
        assertFalse(res.isAllowed());
        assertTrue(res.getReason().contains("Người dùng không tồn tại"));
    }

    @Test
    @DisplayName("checkCameraEntry: Người dùng được gán vào khu vực -> ALLOWED")
    void checkCameraEntry_Allowed() {
        Camera cam = Camera.builder().cameraCode("CAM-01").area(lab).build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("GV001")).thenReturn(Optional.of(lecturer));

        // Gán lecturer vào lab
        assign(lecturer, at(8), null);

        var res = accessDecisionService.checkCameraEntry("CAM-01", "GV001", at(9));
        assertTrue(res.isAllowed());
        assertEquals("CAM-01", res.getCameraCode());
        assertEquals("Phòng Lab 01", res.getAreaName());
        assertEquals("GV001", res.getUserCode());
        assertEquals(AccessSource.ASSIGNED_PERSONNEL, res.getSource());
    }

    @Test
    @DisplayName("checkCameraEntry: Khách tham quan hợp lệ -> ALLOWED")
    void checkCameraEntry_GuestAllowed() {
        UUID guestId = UUID.randomUUID();
        com.fa26se040.icss.entity.Guest guest = com.fa26se040.icss.entity.Guest.builder()
                .id(guestId)
                .fullName("Khách Thăm Quan A")
                .build();
        Camera cam = Camera.builder().cameraCode("CAM-01").area(lab).build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));
        when(userRepository.findByUserCodeAndDeletedAtIsNull(guestId.toString())).thenReturn(Optional.empty());
        when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(guestAccessDecisionService.checkGuestEntry(eq(guestId), eq(lab.getId()), any()))
                .thenReturn(com.fa26se040.icss.dto.guest.GuestAccessDecision.allow(UUID.randomUUID()));

        var res = accessDecisionService.checkCameraEntry("CAM-01", guestId.toString(), at(9));
        assertTrue(res.isAllowed());
        assertEquals("CAM-01", res.getCameraCode());
        assertEquals("Phòng Lab 01", res.getAreaName());
        assertEquals(AccessSource.GUEST_VISIT, res.getSource());
        assertTrue(res.getFullName().contains("Khách Thăm Quan A"));
        assertTrue(res.getReason().contains("Khách tham quan hợp lệ"));
    }

    @Test
    @DisplayName("checkCameraEntry: Khách tham quan đi sai khu vực -> DENIED")
    void checkCameraEntry_GuestDenied_WrongArea() {
        UUID guestId = UUID.randomUUID();
        com.fa26se040.icss.entity.Guest guest = com.fa26se040.icss.entity.Guest.builder()
                .id(guestId)
                .fullName("Khách Thăm Quan B")
                .build();
        Camera cam = Camera.builder().cameraCode("CAM-01").area(lab).build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));
        when(userRepository.findByUserCodeAndDeletedAtIsNull(guestId.toString())).thenReturn(Optional.empty());
        when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(guestAccessDecisionService.checkGuestEntry(eq(guestId), eq(lab.getId()), any()))
                .thenReturn(com.fa26se040.icss.dto.guest.GuestAccessDecision.deny(
                        com.fa26se040.icss.enums.GuestEntryDenyReason.AREA_NOT_IN_VISIT, UUID.randomUUID()));

        var res = accessDecisionService.checkCameraEntry("CAM-01", guestId.toString(), at(9));
        assertFalse(res.isAllowed());
        assertEquals("CAM-01", res.getCameraCode());
        assertEquals(AccessSource.NONE, res.getSource());
        assertTrue(res.getReason().contains("không nằm trong danh sách tiếp đón"));
    }

    // ===================== BR-GD-02 / BR-GD-03: bảo vệ xử lý sự cố (INCIDENT_RESPONSE) =====================

    private static final int INCIDENT_ACCESS_MINUTES = 30;
    private static final int INCIDENT_EXIT_GRACE_MINUTES = 5;
    private static final OffsetDateTime CLAIMED_AT = DAY.withHour(10);

    private User guard(String code, String name) {
        User g = user(code, name);
        g.setRole(Role.GUARD);
        g.setAccessLevel(2);
        lenient().when(userRepository.findById(g.getId())).thenReturn(Optional.of(g));
        return g;
    }

    /** V83: cấp của khu theo loại. */
    private Area area(String name, AreaLevel level) {
        Area a = Area.builder()
                .id(UUID.randomUUID())
                .name(name)
                .areaLevel(level)
                .isActive(true)
                .build();
        lenient().when(areaRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private SecurityIncident incident(Area area, User claimedBy, IncidentStatus status,
                                      OffsetDateTime claimedAt, OffsetDateTime resolvedAt) {
        SecurityIncident i = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-01")
                .area(area)
                .building("A")
                .eventType("INTRUSION")
                .detectedAt(claimedAt.minusMinutes(2))
                .status(status)
                .claimedBy(claimedBy)
                .claimedAt(claimedAt)
                .resolvedAt(resolvedAt)
                .build();
        incidents.add(i);
        return i;
    }

    @Test
    @DisplayName("GD-01: GUARD cấp 2 vào khu Bảo mật, có sự cố CLAIMED tại khu → allow ACCESS_LEVEL, không cần xét sự cố")
    void gd01_guardLevel2_ConfidentialArea_WithClaimedIncident_StillAccessLevel() {
        User g = guard("BV001", "Bảo Vệ A");
        Area hall = area("Sảnh Tầng 2", AreaLevel.CONFIDENTIAL);
        incident(hall, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(g.getId(), hall.getId(), CLAIMED_AT.plusMinutes(10));

        assertTrue(d.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, d.source());
        assertNull(d.sourceRefId());
        verify(guardIncidentAccessRepository, never()).findLatestClaimedByUserInArea(any(), any(), any());
    }

    @Test
    @DisplayName("GD-02: Khu Tuyệt mật, GUARD cấp 2 đã tiếp nhận, at = claimedAt + 10' → allow INCIDENT_RESPONSE, sourceRefId = id sự cố")
    void gd02_level3Area_ClaimedIncident_AllowedIncidentResponse() {
        User g = guard("BV001", "Bảo Vệ A");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        SecurityIncident i = incident(serverRoom, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(10));

        assertTrue(d.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, d.source());
        assertEquals(i.getId(), d.sourceRefId());
        assertEquals("Bảo vệ đang xử lý sự cố tại khu vực", d.reason());
    }

    @Test
    @DisplayName("GD-03: GUARD cấp 1 vào khu Bảo mật (thiếu cấp), đã tiếp nhận sự cố → allow INCIDENT_RESPONSE")
    void gd03_guardBelowAreaLevel_ClaimedIncident_AllowedIncidentResponse() {
        User g = guard("BV001", "Bảo Vệ A");
        g.setAccessLevel(1);
        Area office = area("Văn Phòng Khoa", AreaLevel.CONFIDENTIAL);
        SecurityIncident i = incident(office, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(g.getId(), office.getId(), CLAIMED_AT.plusMinutes(10));

        assertTrue(d.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, d.source());
        assertEquals(i.getId(), d.sourceRefId());
    }

    @Test
    @DisplayName("GUARD cấp 2 (mặc định): vào Bảo mật bằng cấp; vào Tuyệt mật không sự cố → deny, có sự cố đã tiếp nhận → INCIDENT_RESPONSE")
    void guardLevel2_ConfidentialByLevel_HighlyConfidentialOnlyByIncident() {
        User g = guard("BV003", "Bảo Vệ C");
        Area lab2 = area("Phòng Lab Bảo Mật", AreaLevel.CONFIDENTIAL);
        Area serverRoom = area("Phòng Máy Chủ Tuyệt Mật", AreaLevel.HIGHLY_CONFIDENTIAL);

        AccessDecision confidential = accessDecisionService.checkEntry(g.getId(), lab2.getId(), CLAIMED_AT.plusMinutes(10));
        assertTrue(confidential.allowed());
        assertEquals(AccessSource.ACCESS_LEVEL, confidential.source());

        AccessDecision noIncident = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(10));
        assertFalse(noIncident.allowed());
        assertEquals(AccessSource.NONE, noIncident.source());

        SecurityIncident i = incident(serverRoom, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);
        AccessDecision withIncident = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(10));
        assertTrue(withIncident.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, withIncident.source());
        assertEquals(i.getId(), withIncident.sourceRefId());
    }

    @Test
    @DisplayName("GD-04: claimedAt + 30' (mốc loại trừ) → deny; claimedAt + 29' → allow; đúng claimedAt → allow, trước đó → deny")
    void gd04_accessWindowBoundaries() {
        User g = guard("BV001", "Bảo Vệ A");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        incident(serverRoom, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision atEnd = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(30));
        assertFalse(atEnd.allowed());
        assertEquals(AccessSource.NONE, atEnd.source());

        AccessDecision beforeEnd = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(29));
        assertTrue(beforeEnd.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, beforeEnd.source());

        assertTrue(accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT).allowed());
        assertFalse(accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.minusMinutes(1)).allowed());
    }

    @Test
    @DisplayName("GD-05: Sự cố đã xử lý xong, at = resolvedAt + 3' (ân hạn 5') → allow; resolvedAt + 5' → deny")
    void gd05_resolvedIncident_ExitGrace() {
        User g = guard("BV001", "Bảo Vệ A");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        OffsetDateTime resolvedAt = CLAIMED_AT.plusMinutes(10);
        SecurityIncident i = incident(serverRoom, g, IncidentStatus.RESOLVED_VERIFIED, CLAIMED_AT, resolvedAt);

        AccessDecision inGrace = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), resolvedAt.plusMinutes(3));
        assertTrue(inGrace.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, inGrace.source());
        assertEquals(i.getId(), inGrace.sourceRefId());

        AccessDecision graceEnd = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), resolvedAt.plusMinutes(5));
        assertFalse(graceEnd.allowed());
        assertEquals(AccessSource.NONE, graceEnd.source());
    }

    @Test
    @DisplayName("GD-06: resolvedAt + ân hạn vượt claimedAt + 30' → lấy mốc sớm hơn (claimedAt + 30')")
    void gd06_resolvedIncident_GraceCappedByAccessWindow() {
        User g = guard("BV001", "Bảo Vệ A");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        OffsetDateTime resolvedAt = CLAIMED_AT.plusMinutes(28); // resolvedAt + 5' = claimedAt + 33'
        incident(serverRoom, g, IncidentStatus.RESOLVED_DISMISSED, CLAIMED_AT, resolvedAt);

        assertTrue(accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(29)).allowed());

        AccessDecision atCap = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(30));
        assertFalse(atCap.allowed(), "Mốc claimedAt + 30' sớm hơn resolvedAt + 5' nên là mốc kết thúc");

        AccessDecision stillInGrace = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), resolvedAt.plusMinutes(3));
        assertFalse(stillInGrace.allowed(), "Còn trong ân hạn nhưng đã quá claimedAt + 30' → deny");
        assertEquals(AccessSource.NONE, stillInGrace.source());
    }

    @Test
    @DisplayName("GD-07: GUARD tiếp nhận sự cố ở khu Y nhưng vào khu X cấp 3 → deny")
    void gd07_incidentInOtherArea_Denied() {
        User g = guard("BV001", "Bảo Vệ A");
        Area areaX = area("Phòng Máy Chủ X", AreaLevel.HIGHLY_CONFIDENTIAL);
        Area areaY = area("Phòng Máy Chủ Y", AreaLevel.HIGHLY_CONFIDENTIAL);
        incident(areaY, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(g.getId(), areaX.getId(), CLAIMED_AT.plusMinutes(10));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        assertNull(d.sourceRefId());
    }

    @Test
    @DisplayName("GD-08: GUARD khác (không phải người tiếp nhận) vào khu X → deny")
    void gd08_otherGuard_Denied() {
        User claimer = guard("BV001", "Bảo Vệ A");
        User otherGuard = guard("BV002", "Bảo Vệ B");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        incident(serverRoom, claimer, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(otherGuard.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(10));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
    }

    @Test
    @DisplayName("GD-09: NORMAL_USER, có sự cố tại khu X (kể cả claimedBy là chính user) → rule không áp, deny")
    void gd09_normalUser_RuleNotApplied() {
        User normal = user("SE170555", "Sinh Viên C");
        normal.setAccessLevel(1);
        lenient().when(userRepository.findById(normal.getId())).thenReturn(Optional.of(normal));
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        incident(serverRoom, normal, IncidentStatus.CLAIMED, CLAIMED_AT, null);

        AccessDecision d = accessDecisionService.checkEntry(normal.getId(), serverRoom.getId(), CLAIMED_AT.plusMinutes(10));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        verify(guardIncidentAccessRepository, never()).findLatestClaimedByUserInArea(any(), any(), any());
    }

    @Test
    @DisplayName("GD-10: Đổi GUARD_INCIDENT_ACCESS_MINUTES từ 30 xuống 5 giữa 2 lần gọi → lần sau dùng 5")
    void gd10_configChangeTakesEffectOnNextCall() {
        User g = guard("BV001", "Bảo Vệ A");
        Area serverRoom = area("Phòng Máy Chủ", AreaLevel.HIGHLY_CONFIDENTIAL);
        incident(serverRoom, g, IncidentStatus.CLAIMED, CLAIMED_AT, null);
        OffsetDateTime at = CLAIMED_AT.plusMinutes(10);

        AccessDecision before = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), at);
        assertTrue(before.allowed());
        assertEquals(AccessSource.INCIDENT_RESPONSE, before.source());

        when(systemConfigService.getInt(ConfigKey.GUARD_INCIDENT_ACCESS_MINUTES)).thenReturn(5);

        AccessDecision after = accessDecisionService.checkEntry(g.getId(), serverRoom.getId(), at);
        assertFalse(after.allowed(), "claimedAt + 10' đã quá cửa sổ 5'");
        assertEquals(AccessSource.NONE, after.source());
        verify(systemConfigService, times(2)).getInt(ConfigKey.GUARD_INCIDENT_ACCESS_MINUTES);
    }

    // ===================== Sai giờ chỉ xét đơn cùng ngày (giờ VN) + camera DECOMMISSIONED =====================

    /** V83: không còn lý do "chỉ định đích danh"; sinh viên cấp 1 vào lab Tuyệt mật bị từ chối vì thiếu cấp. */
    private static final String LEVEL_DENY = "Cấp độ truy cập không đủ (Khu vực Cấp 3, người dùng Cấp 1)";

    // Giả lập đúng điều kiện JPQL findAnyApprovedRequestsForUser (mọi đơn APPROVED của user tại khu, startTime giảm dần)
    private void stubAnyApprovedRequests() {
        lenient().when(accessRequestRepository.findAnyApprovedRequestsForUser(any(), any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            UUID areaId = inv.getArgument(1);
            RequestStatus status = inv.getArgument(2);
            return requests.stream()
                    .filter(r -> r.getArea().getId().equals(areaId))
                    .filter(r -> r.getStatus() == status)
                    .filter(r -> r.getRequester().getId().equals(userId)
                            || r.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(userId)))
                    .sorted(Comparator.comparing(AccessRequest::getStartTime).reversed())
                    .toList();
        });
    }

    @Test
    @DisplayName("SD-01: Đơn APPROVED 08:00–10:00 hôm nay, lúc 07:00 → deny \"Chưa tới khung giờ…\"")
    void sd01_sameDayBeforeStart_NotYetStarted() {
        stubAnyApprovedRequests();
        request(student, RequestStatus.APPROVED, at(8), at(10));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(7));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        assertEquals("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 08:00 05/10/2026)", d.reason());
    }

    @Test
    @DisplayName("SD-02: Đơn APPROVED 08:00–10:00 hôm nay, lúc 11:00 → deny \"…hết hạn sử dụng trong ngày…\"")
    void sd02_sameDayAfterEnd_ExpiredToday() {
        stubAnyApprovedRequests();
        request(student, RequestStatus.APPROVED, at(8), at(10));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(11));

        assertFalse(d.allowed());
        assertEquals("Đơn xin quyền đã hết hạn sử dụng trong ngày (Đã kết thúc lúc: 10:00 05/10/2026)", d.reason());
    }

    @Test
    @DisplayName("SD-03: Đơn APPROVED của hôm qua, vào hôm nay → deny thường, không gắn nhãn sai giờ")
    void sd03_yesterdayRequest_PlainDeny() {
        stubAnyApprovedRequests();
        student.setAccessLevel(1);
        request(student, RequestStatus.APPROVED, at(8).minusDays(1), at(10).minusDays(1));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(11));

        assertFalse(d.allowed());
        assertEquals(AccessSource.NONE, d.source());
        assertEquals(LEVEL_DENY, d.reason());
    }

    @Test
    @DisplayName("SD-04: Đơn hôm qua + đơn hôm nay 14:00–16:00, lúc 13:00 → chọn đơn hôm nay, \"Chưa tới khung giờ\"")
    void sd04_picksTodayRequest() {
        stubAnyApprovedRequests();
        request(student, RequestStatus.APPROVED, at(8).minusDays(1), at(10).minusDays(1));
        request(student, RequestStatus.APPROVED, at(14), at(16));

        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(13));
        assertEquals("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 14:00 05/10/2026)", d.reason());

        // Thêm đơn ngày mai (đứng đầu theo startTime giảm dần) → vẫn chọn đơn hôm nay
        request(student, RequestStatus.APPROVED, at(8).plusDays(1), at(10).plusDays(1));
        AccessDecision withTomorrow = accessDecisionService.checkEntry(student.getId(), lab.getId(), at(13));
        assertEquals("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 14:00 05/10/2026)", withTomorrow.reason());
    }

    @Test
    @DisplayName("SD-05: at = 23:30 UTC (= 06:30 VN hôm sau) → xét theo ngày VN")
    void sd05_vietnamCalendarDay() {
        stubAnyApprovedRequests();
        student.setAccessLevel(1);
        OffsetDateTime utc2330 = OffsetDateTime.of(2026, 10, 5, 23, 30, 0, 0, ZoneOffset.UTC); // 06/10 06:30 giờ VN

        // Đơn 06/10 08:00–10:00 giờ VN: cùng ngày VN với at (khác ngày UTC) → "Chưa tới khung giờ"
        request(student, RequestStatus.APPROVED, at(8).plusDays(1), at(10).plusDays(1));
        AccessDecision d = accessDecisionService.checkEntry(student.getId(), lab.getId(), utc2330);
        assertEquals("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 08:00 06/10/2026)", d.reason());

        // Đơn 05/10 20:00–22:00 giờ VN: cùng ngày UTC với at nhưng khác ngày VN → không gắn nhãn sai giờ
        requests.clear();
        request(student, RequestStatus.APPROVED, at(20), at(22));
        AccessDecision other = accessDecisionService.checkEntry(student.getId(), lab.getId(), utc2330);
        assertEquals(LEVEL_DENY, other.reason());
    }

    @Test
    @DisplayName("DC-01: Camera DECOMMISSIONED → deny, không xét người dùng, không ghi nhật ký")
    void dc01_decommissionedCamera_DeniedWithoutLog() {
        Camera cam = Camera.builder().cameraCode("CAM-01").status(CameraStatus.DECOMMISSIONED).area(lab).build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-01")).thenReturn(Optional.of(cam));

        var res = accessDecisionService.checkCameraEntry("CAM-01", "GV001", at(9));

        assertFalse(res.isAllowed());
        assertEquals(AccessSource.NONE, res.getSource());
        assertEquals("Camera đã ngừng hoạt động (DECOMMISSIONED)", res.getReason());
        verify(userRepository, never()).findByUserCodeAndDeletedAtIsNull(any());
        verify(cameraAccessLogService, never()).recordAccessLog(any(), any());
    }
}
