package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.GuardTeamDispatchAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.guard.AvailableSubstituteDto;
import com.fa26se040.icss.dto.guard.GuardTeamDispatchCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDispatchDto;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuardTeamDispatchRepository;
import com.fa26se040.icss.repository.GuardTeamRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardTeamDispatchServiceTest {

    @Mock
    private GuardTeamDispatchRepository dispatchRepository;

    @Mock
    private GuardTeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GuardShiftRepository shiftRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private GuardTeamDispatchService dispatchService;

    private GuardTeam teamAlpha;
    private GuardTeam teamBeta;
    private User guard1; // Team Beta
    private User guard2; // Team Alpha (Target team)
    private User guard3; // Unassigned

    @BeforeEach
    void setUp() {
        teamAlpha = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội Alpha")
                .build();

        teamBeta = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội Beta")
                .build();

        guard1 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyễn Văn Beta")
                .userCode("BV001")
                .email("beta@icss.com")
                .role(Role.GUARD)
                .team(teamBeta)
                .isActive(true)
                .build();

        guard2 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trần Văn Alpha")
                .userCode("BV002")
                .email("alpha@icss.com")
                .role(Role.GUARD)
                .team(teamAlpha)
                .isActive(true)
                .build();

        guard3 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Lê Văn Tự Do")
                .userCode("BV003")
                .email("free@icss.com")
                .role(Role.GUARD)
                .team(null)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Chỉ tìm người khả dụng: Cho phép cả bảo vệ cùng đội hoặc khác đội nếu đang rảnh ca")
    void getAvailableGuards_AllowsSameTeamIfFree() {
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guard1, guard2, guard3));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        List<AvailableSubstituteDto> available = dispatchService.getAvailableGuardsForDispatch(
                teamAlpha.getId(), startDate, endDate, ShiftType.SHIFT_MORNING
        );

        // Cả 3 người (guard1: đội khác, guard2: cùng đội, guard3: tự do) đều khả dụng vì không vướng lịch
        assertEquals(3, available.size());
        assertTrue(available.stream().anyMatch(g -> g.getId().equals(guard1.getId())));
        assertTrue(available.stream().anyMatch(g -> g.getId().equals(guard2.getId())));
        assertTrue(available.stream().anyMatch(g -> g.getId().equals(guard3.getId())));
    }

    @Test
    @DisplayName("Chỉ tìm người khả dụng: Loại trừ bảo vệ có ca trực trùng ngày (Rule 1)")
    void getAvailableGuards_ExcludesGuardWithShiftOnSameDay() {
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guard1));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // guard1 đã có ca trực chiều ngày 24/09
        GuardShift existingShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(startDate)
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                eq(guard1.getId()), any(), any()
        )).thenReturn(List.of(existingShift));

        List<AvailableSubstituteDto> available = dispatchService.getAvailableGuardsForDispatch(
                teamAlpha.getId(), startDate, endDate, ShiftType.SHIFT_MORNING
        );

        // Bị loại vì đã có ca trực cùng ngày
        assertTrue(available.isEmpty());
    }

    @Test
    @DisplayName("Chỉ tìm người khả dụng: Loại trừ bảo vệ trực ca đêm hôm trước khi điều động ca sáng (Rule 2A - 0h nghỉ)")
    void getAvailableGuards_ExcludesGuardWithNightShiftYesterday() {
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guard1));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // guard1 trực ca đêm ngày 23/09 (kết thúc 06:00 sáng ngày 24/09)
        GuardShift nightShiftYesterday = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(startDate.minusDays(1))
                .shiftType(ShiftType.SHIFT_NIGHT)
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                eq(guard1.getId()), any(), any()
        )).thenReturn(List.of(nightShiftYesterday));

        List<AvailableSubstituteDto> available = dispatchService.getAvailableGuardsForDispatch(
                teamAlpha.getId(), startDate, endDate, ShiftType.SHIFT_MORNING
        );

        // Bị loại vì 0 giờ nghỉ ngơi giữa đêm qua và sáng nay
        assertTrue(available.isEmpty());
    }

    @Test
    @DisplayName("Chỉ tìm người khả dụng: Loại trừ bảo vệ có ca sáng hôm sau khi điều động ca đêm (Rule 2B - 0h nghỉ)")
    void getAvailableGuards_ExcludesGuardWithMorningShiftTomorrow() {
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guard1));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // guard1 có lịch ca sáng ngày 25/09 (bắt đầu 06:00 ngay khi ca đêm 24/09 kết thúc)
        GuardShift morningShiftTomorrow = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(endDate.plusDays(1))
                .shiftType(ShiftType.SHIFT_MORNING)
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                eq(guard1.getId()), any(), any()
        )).thenReturn(List.of(morningShiftTomorrow));

        List<AvailableSubstituteDto> available = dispatchService.getAvailableGuardsForDispatch(
                teamAlpha.getId(), startDate, endDate, ShiftType.SHIFT_NIGHT
        );

        // Bị loại vì 0 giờ nghỉ ngơi giữa đêm nay và sáng mai
        assertTrue(available.isEmpty());
    }

    @Test
    @DisplayName("Lưu điều động: Chặn và ném ngoại lệ nếu bảo vệ bị trùng lịch trực")
    void createDispatches_ThrowsExceptionWhenConflict() {
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        when(teamRepository.findById(teamAlpha.getId())).thenReturn(Optional.of(teamAlpha));
        when(userRepository.findById(guard1.getId())).thenReturn(Optional.of(guard1));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // guard1 đã có ca trực sáng ngày 24/09
        GuardShift existingShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(startDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                eq(guard1.getId()), any(), any()
        )).thenReturn(List.of(existingShift));

        GuardTeamDispatchCreateRequest req = GuardTeamDispatchCreateRequest.builder()
                .toTeamId(teamAlpha.getId())
                .guardIds(List.of(guard1.getId()))
                .startDate(startDate)
                .endDate(endDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .reason("Trực lễ")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                dispatchService.createDispatches(req, "manager@icss.com")
        );

        assertTrue(ex.getMessage().contains("đã có lịch trực"));
    }

    @Test
    @DisplayName("Audit: Tạo, hủy và tự động hoàn tất điều động tăng cường đều ghi audit GUARD_TEAM_DISPATCH")
    void dispatchLifecycle_RecordsAudit() {
        User manager = User.builder()
                .id(UUID.randomUUID())
                .fullName("Quản lý")
                .email("manager@icss.com")
                .role(Role.FACILITY_MANAGER)
                .build();
        LocalDate startDate = LocalDate.of(2026, 9, 24);
        LocalDate endDate = LocalDate.of(2026, 9, 24);

        UUID dispatchId = UUID.randomUUID();
        when(teamRepository.findById(teamAlpha.getId())).thenReturn(Optional.of(teamAlpha));
        when(userRepository.findByEmail("manager@icss.com")).thenReturn(Optional.of(manager));
        when(userRepository.findById(guard1.getId())).thenReturn(Optional.of(guard1));
        when(dispatchRepository.findOverlappingDispatches(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(eq(guard1.getId()), any(), any()))
                .thenReturn(Collections.emptyList());
        when(areaRepository.findAll()).thenReturn(Collections.emptyList());
        when(dispatchRepository.save(any(GuardTeamDispatch.class))).thenAnswer(inv -> {
            GuardTeamDispatch d = inv.getArgument(0);
            if (d.getId() == null) d.setId(dispatchId);
            return d;
        });

        GuardTeamDispatchCreateRequest req = GuardTeamDispatchCreateRequest.builder()
                .toTeamId(teamAlpha.getId())
                .guardIds(List.of(guard1.getId()))
                .startDate(startDate)
                .endDate(endDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .reason("Tăng cường lễ khai giảng")
                .build();

        List<GuardTeamDispatchDto> created = dispatchService.createDispatches(req, "manager@icss.com");
        assertEquals(1, created.size());
        verify(auditService).record(
                eq(AuditTargetType.GUARD_TEAM_DISPATCH),
                eq(AuditAction.CREATE),
                eq(dispatchId.toString()),
                isNull(),
                eq(guard1),
                isNull(),
                any(GuardTeamDispatchAuditSnapshot.class),
                eq("Tăng cường lễ khai giảng"),
                argThat((AuditActor actor) -> actor != null && "USER".equals(actor.getActorType()) && manager.equals(actor.getUser()))
        );

        // CANCEL
        GuardTeamDispatch activeDispatch = GuardTeamDispatch.builder()
                .id(dispatchId)
                .guard(guard1)
                .fromTeam(teamBeta)
                .toTeam(teamAlpha)
                .startDate(startDate)
                .endDate(endDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .status(GuardDispatchStatus.ACTIVE)
                .reason("Tăng cường lễ khai giảng")
                .build();
        when(dispatchRepository.findById(dispatchId)).thenReturn(Optional.of(activeDispatch));
        dispatchService.cancelDispatch(dispatchId, "manager@icss.com");
        verify(auditService).record(
                eq(AuditTargetType.GUARD_TEAM_DISPATCH),
                eq(AuditAction.CANCEL),
                eq(dispatchId.toString()),
                isNull(),
                eq(guard1),
                argThat((GuardTeamDispatchAuditSnapshot snap) -> snap != null && snap.status() == GuardDispatchStatus.ACTIVE),
                argThat((GuardTeamDispatchAuditSnapshot snap) -> snap != null && snap.status() == GuardDispatchStatus.CANCELLED),
                anyString(),
                argThat((AuditActor actor) -> actor != null && "USER".equals(actor.getActorType()) && manager.equals(actor.getUser()))
        );

        // EXPIRE
        UUID expiredId = UUID.randomUUID();
        GuardTeamDispatch expiredDispatch = GuardTeamDispatch.builder()
                .id(expiredId)
                .guard(guard1)
                .fromTeam(teamBeta)
                .toTeam(teamAlpha)
                .startDate(LocalDate.now().minusDays(2))
                .endDate(LocalDate.now().minusDays(1))
                .status(GuardDispatchStatus.ACTIVE)
                .build();
        when(dispatchRepository.findAllByStatusAndEndDateBefore(eq(GuardDispatchStatus.ACTIVE), any(LocalDate.class)))
                .thenReturn(List.of(expiredDispatch));
        dispatchService.autoExpireDispatches();
        verify(auditService).record(
                eq(AuditTargetType.GUARD_TEAM_DISPATCH),
                eq(AuditAction.EXPIRE),
                eq(expiredId.toString()),
                isNull(),
                eq(guard1),
                argThat((GuardTeamDispatchAuditSnapshot snap) -> snap != null && snap.status() == GuardDispatchStatus.ACTIVE),
                argThat((GuardTeamDispatchAuditSnapshot snap) -> snap != null && snap.status() == GuardDispatchStatus.COMPLETED),
                anyString(),
                argThat((AuditActor actor) -> actor != null && "SYSTEM".equals(actor.getActorType()) && "GUARD_DISPATCH_EXPIRE_JOB".equals(actor.getActorSource()))
        );
    }
}
