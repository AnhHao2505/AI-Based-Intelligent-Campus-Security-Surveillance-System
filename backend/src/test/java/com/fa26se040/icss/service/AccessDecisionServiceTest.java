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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                .areaLevel(AreaLevel.PRIVATE)
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
}
