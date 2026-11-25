package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.GuardShiftAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.GuardShiftRequestAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guard.GuardShiftRequestCreateDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestResponseDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestReviewDto;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import com.fa26se040.icss.enums.GuardShiftRequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuardShiftRequestRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardShiftRequestServiceTest {

    @Mock
    private GuardShiftRequestRepository requestRepository;

    @Mock
    private GuardShiftRepository shiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private GuardShiftRequestService requestService;

    private User requester;
    private User substitute;
    private User reviewer;
    private GuardShift shift;
    private GuardShift targetShift;

    @BeforeEach
    void setUp() {
        requester = User.builder()
                .id(UUID.randomUUID())
                .fullName("Bảo vệ A")
                .userCode("BV-01")
                .email("bva@icss.com")
                .role(Role.GUARD)
                .build();
        substitute = User.builder()
                .id(UUID.randomUUID())
                .fullName("Bảo vệ B")
                .userCode("BV-02")
                .email("bvb@icss.com")
                .role(Role.GUARD)
                .build();
        reviewer = User.builder()
                .id(UUID.randomUUID())
                .fullName("Quản lý")
                .userCode("FM-01")
                .email("fm@icss.com")
                .role(Role.FACILITY_MANAGER)
                .build();

        LocalDate futureDate = LocalDate.now().plusDays(3);
        shift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(requester)
                .shiftDate(futureDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        targetShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(substitute)
                .shiftDate(futureDate.plusDays(1))
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(22, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();
    }

    @Test
    @DisplayName("Audit: Tạo, phê duyệt (SWAP_SHIFT) và từ chối đơn đều ghi audit GUARD_SHIFT_REQUEST & GUARD_SHIFT")
    void createApproveSwapAndReject_RecordAudit() {
        UUID reqId = UUID.randomUUID();
        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(shiftRepository.findById(shift.getId())).thenReturn(Optional.of(shift));
        when(shiftRepository.findById(targetShift.getId())).thenReturn(Optional.of(targetShift));
        when(requestRepository.existsByShiftIdAndStatus(shift.getId(), GuardShiftRequestStatus.PENDING)).thenReturn(false);
        when(requestRepository.save(any(GuardShiftRequest.class))).thenAnswer(inv -> {
            GuardShiftRequest r = inv.getArgument(0);
            if (r.getId() == null) r.setId(reqId);
            return r;
        });

        GuardShiftRequestCreateDto createDto = GuardShiftRequestCreateDto.builder()
                .shiftId(shift.getId())
                .requestType(GuardShiftRequestType.SWAP_SHIFT)
                .targetShiftId(targetShift.getId())
                .reason("Đổi ca bận việc gia đình")
                .build();

        GuardShiftRequestResponseDto created = requestService.createRequest(createDto, requester.getEmail());
        assertEquals(GuardShiftRequestStatus.PENDING, created.getStatus());
        verify(auditService).record(
                eq(AuditTargetType.GUARD_SHIFT_REQUEST),
                eq(AuditAction.CREATE),
                eq(reqId.toString()),
                isNull(),
                eq(requester),
                isNull(),
                any(GuardShiftRequestAuditSnapshot.class),
                eq("Đổi ca bận việc gia đình"),
                argThat((AuditActor a) -> a != null && "USER".equals(a.getActorType()) && requester.equals(a.getUser()))
        );

        // APPROVE SWAP_SHIFT
        GuardShiftRequest pendingReq = GuardShiftRequest.builder()
                .id(reqId)
                .requester(requester)
                .shift(shift)
                .requestType(GuardShiftRequestType.SWAP_SHIFT)
                .substituteGuard(substitute)
                .targetShift(targetShift)
                .reason("Đổi ca bận việc gia đình")
                .status(GuardShiftRequestStatus.PENDING)
                .build();
        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(requestRepository.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftRequestReviewDto approveDto = new GuardShiftRequestReviewDto();
        approveDto.setReviewNotes("Đồng ý đổi ca");
        GuardShiftRequestResponseDto approved = requestService.approveRequest(reqId, approveDto, reviewer.getEmail());
        assertEquals(GuardShiftRequestStatus.APPROVED, approved.getStatus());

        verify(auditService, times(2)).record(
                eq(AuditTargetType.GUARD_SHIFT),
                eq(AuditAction.UPDATE),
                anyString(),
                isNull(),
                any(User.class),
                any(GuardShiftAuditSnapshot.class),
                any(GuardShiftAuditSnapshot.class),
                eq("Chấp thuận đổi ca trực"),
                argThat((AuditActor a) -> a != null && "USER".equals(a.getActorType()) && reviewer.equals(a.getUser()))
        );
        verify(auditService).record(
                eq(AuditTargetType.GUARD_SHIFT_REQUEST),
                eq(AuditAction.APPROVE),
                eq(reqId.toString()),
                isNull(),
                eq(requester),
                any(GuardShiftRequestAuditSnapshot.class),
                argThat((GuardShiftRequestAuditSnapshot snap) -> snap != null && snap.status() == GuardShiftRequestStatus.APPROVED),
                eq("Đồng ý đổi ca"),
                argThat((AuditActor a) -> a != null && "USER".equals(a.getActorType()) && reviewer.equals(a.getUser()))
        );

        // REJECT another request
        UUID req2Id = UUID.randomUUID();
        GuardShiftRequest pendingReq2 = GuardShiftRequest.builder()
                .id(req2Id)
                .requester(requester)
                .shift(shift)
                .requestType(GuardShiftRequestType.LEAVE_REQUEST)
                .reason("Xin nghỉ phép")
                .status(GuardShiftRequestStatus.PENDING)
                .build();
        when(requestRepository.findById(req2Id)).thenReturn(Optional.of(pendingReq2));

        GuardShiftRequestReviewDto rejectDto = new GuardShiftRequestReviewDto();
        rejectDto.setReviewNotes("Thiếu nhân sự");
        GuardShiftRequestResponseDto rejected = requestService.rejectRequest(req2Id, rejectDto, reviewer.getEmail());
        assertEquals(GuardShiftRequestStatus.REJECTED, rejected.getStatus());
        verify(auditService).record(
                eq(AuditTargetType.GUARD_SHIFT_REQUEST),
                eq(AuditAction.REJECT),
                eq(req2Id.toString()),
                isNull(),
                eq(requester),
                any(GuardShiftRequestAuditSnapshot.class),
                argThat((GuardShiftRequestAuditSnapshot snap) -> snap != null && snap.status() == GuardShiftRequestStatus.REJECTED),
                eq("Thiếu nhân sự"),
                argThat((AuditActor a) -> a != null && "USER".equals(a.getActorType()) && reviewer.equals(a.getUser()))
        );
    }

    @Test
    @DisplayName("Audit: Duyệt đơn nghỉ phép không có người thay -> hủy ca và ghi audit GUARD_SHIFT / CANCEL")
    void approveLeaveWithoutSubstitute_CancelsShiftAndRecordsAudit() {
        UUID reqId = UUID.randomUUID();
        GuardShiftRequest leaveReq = GuardShiftRequest.builder()
                .id(reqId)
                .requester(requester)
                .shift(shift)
                .requestType(GuardShiftRequestType.LEAVE_REQUEST)
                .reason("Nghỉ ốm")
                .status(GuardShiftRequestStatus.PENDING)
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(requestRepository.findById(reqId)).thenReturn(Optional.of(leaveReq));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepository.save(any(GuardShiftRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        requestService.approveRequest(reqId, null, reviewer.getEmail());

        assertEquals(ShiftStatus.CANCELLED, shift.getStatus());
        verify(auditService).record(
                eq(AuditTargetType.GUARD_SHIFT),
                eq(AuditAction.CANCEL),
                eq(shift.getId().toString()),
                isNull(),
                eq(requester),
                any(GuardShiftAuditSnapshot.class),
                argThat((GuardShiftAuditSnapshot snap) -> snap != null && snap.status() == ShiftStatus.CANCELLED),
                anyString(),
                any(AuditActor.class)
        );
        verify(auditService).record(
                eq(AuditTargetType.GUARD_SHIFT_REQUEST),
                eq(AuditAction.APPROVE),
                eq(reqId.toString()),
                isNull(),
                eq(requester),
                any(GuardShiftRequestAuditSnapshot.class),
                any(GuardShiftRequestAuditSnapshot.class),
                isNull(),
                any(AuditActor.class)
        );
    }
}
