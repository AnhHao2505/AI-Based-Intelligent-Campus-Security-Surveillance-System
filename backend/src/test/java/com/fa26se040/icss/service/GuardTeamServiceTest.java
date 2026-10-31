package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GuardTeamCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDto;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardTeamServiceTest {

    @Mock
    private GuardTeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private GuardShiftRepository shiftRepository;

    @InjectMocks
    private GuardTeamService guardTeamService;

    private GuardTeam activeTeam;
    private GuardTeam deletedTeam;

    @BeforeEach
    void setUp() {
        activeTeam = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội 1")
                .description("Đội an ninh số 1")
                .isActive(true)
                .members(new ArrayList<>())
                .build();

        deletedTeam = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội 1")
                .description("Đội an ninh cũ đã xóa")
                .isActive(false)
                .members(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("Tạo mới đội bảo vệ thành công khi tên chưa tồn tại")
    void testCreateTeam_Success_New() {
        GuardTeamCreateRequest request = GuardTeamCreateRequest.builder()
                .teamName("Đội 2")
                .description("Đội mới")
                .build();

        when(teamRepository.findByTeamNameIgnoreCase("Đội 2")).thenReturn(Optional.empty());
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(invocation -> {
            GuardTeam t = invocation.getArgument(0);
            t.setId(UUID.randomUUID());
            t.setMembers(new ArrayList<>());
            return t;
        });

        GuardTeamDto dto = guardTeamService.createTeam(request);

        assertNotNull(dto);
        assertEquals("Đội 2", dto.getTeamName());
        verify(teamRepository, times(1)).save(any(GuardTeam.class));
    }

    @Test
    @DisplayName("Tạo đội bảo vệ với tên của đội đã bị xóa -> Tái kích hoạt đội cũ")
    void testCreateTeam_ReactivateDeleted() {
        GuardTeamCreateRequest request = GuardTeamCreateRequest.builder()
                .teamName("Đội 1")
                .description("Mô tả mới")
                .build();

        when(teamRepository.findByTeamNameIgnoreCase("Đội 1")).thenReturn(Optional.of(deletedTeam));
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GuardTeamDto dto = guardTeamService.createTeam(request);

        assertNotNull(dto);
        assertEquals("Đội 1", dto.getTeamName());
        assertTrue(deletedTeam.getIsActive());
        assertEquals("Mô tả mới", deletedTeam.getDescription());
        verify(teamRepository, times(1)).save(deletedTeam);
    }

    @Test
    @DisplayName("Tạo đội bảo vệ báo lỗi khi tên đang tồn tại và đang hoạt động")
    void testCreateTeam_ConflictActive() {
        GuardTeamCreateRequest request = GuardTeamCreateRequest.builder()
                .teamName("Đội 1")
                .build();

        when(teamRepository.findByTeamNameIgnoreCase("Đội 1")).thenReturn(Optional.of(activeTeam));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardTeamService.createTeam(request));
        assertTrue(ex.getMessage().contains("Tên tổ đội đã tồn tại"));
    }

    @Test
    @DisplayName("Xóa đội bảo vệ -> Chuyển isActive = false và gỡ bỏ liên kết thành viên")
    void testDeleteTeam_Success() {
        UUID teamId = activeTeam.getId();
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(activeTeam));
        when(userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamId)).thenReturn(new ArrayList<>());

        guardTeamService.deleteTeam(teamId);

        assertFalse(activeTeam.getIsActive());
        verify(teamRepository, times(1)).save(activeTeam);
    }

    @Test
    @DisplayName("P1b: Tạo mới đội bảo vệ có gắn tòa nhà thành công")
    void testCreateTeam_WithBuilding() {
        UUID buildingId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).name("Tòa Alpha").build();
        GuardTeamCreateRequest request = GuardTeamCreateRequest.builder()
                .teamName("Đội Alpha")
                .description("Đội Alpha")
                .buildingId(buildingId)
                .build();

        when(teamRepository.findByTeamNameIgnoreCase("Đội Alpha")).thenReturn(Optional.empty());
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(building));
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(inv -> {
            GuardTeam t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        GuardTeamDto dto = guardTeamService.createTeam(request);

        assertNotNull(dto);
        assertEquals("Đội Alpha", dto.getTeamName());
        assertEquals(buildingId, dto.getBuildingId());
        assertEquals("Tòa Alpha", dto.getBuildingName());
    }

    @Test
    @DisplayName("P1b (BR-GB-06): Xem trước đổi tòa nhà - Không có ca trực tương lai -> canChange = true")
    void testPreviewBuildingChange_NoBlockers() {
        UUID teamId = activeTeam.getId();
        UUID oldBuildingId = UUID.randomUUID();
        Building oldBuilding = Building.builder().id(oldBuildingId).name("Tòa Alpha").build();
        activeTeam.setBuilding(oldBuilding);

        UUID targetBuildingId = UUID.randomUUID();
        Building targetBuilding = Building.builder().id(targetBuildingId).name("Tòa Beta").build();

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(activeTeam));
        when(buildingRepository.findById(targetBuildingId)).thenReturn(Optional.of(targetBuilding));
        when(shiftRepository.findFutureScheduledShiftsInBuilding(eq(teamId), any(LocalDate.class), eq(oldBuildingId)))
                .thenReturn(List.of());

        var preview = guardTeamService.previewBuildingChange(teamId, targetBuildingId);

        assertNotNull(preview);
        assertTrue(preview.canChange());
        assertTrue(preview.blockers().isEmpty());
    }

    @Test
    @DisplayName("P1b (BR-GB-06): Xem trước đổi tòa nhà - Còn ca SCHEDULED ở tòa cũ -> canChange = false kèm danh sách ca")
    void testPreviewBuildingChange_WithBlockers() {
        UUID teamId = activeTeam.getId();
        UUID oldBuildingId = UUID.randomUUID();
        Building oldBuilding = Building.builder().id(oldBuildingId).name("Tòa Alpha").build();
        activeTeam.setBuilding(oldBuilding);

        UUID targetBuildingId = UUID.randomUUID();
        Building targetBuilding = Building.builder().id(targetBuildingId).name("Tòa Beta").build();

        User member = User.builder().id(UUID.randomUUID()).fullName("Bảo vệ 1").userCode("SEC-001").build();
        GuardShift scheduledShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(member)
                .shiftDate(LocalDate.now().plusDays(1))
                .shiftType(ShiftType.SHIFT_MORNING)
                .status(ShiftStatus.SCHEDULED)
                .building(oldBuilding)
                .build();

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(activeTeam));
        when(buildingRepository.findById(targetBuildingId)).thenReturn(Optional.of(targetBuilding));
        when(shiftRepository.findFutureScheduledShiftsInBuilding(eq(teamId), any(LocalDate.class), eq(oldBuildingId)))
                .thenReturn(List.of(scheduledShift));

        var preview = guardTeamService.previewBuildingChange(teamId, targetBuildingId);

        assertNotNull(preview);
        assertFalse(preview.canChange());
        assertEquals(1, preview.blockers().size());
        assertEquals(1, preview.blockers().get(0).count());
        assertEquals("SCHEDULED_SHIFTS_IN_CURRENT_BUILDING", preview.blockers().get(0).type());
        assertEquals(1, preview.blockers().get(0).shifts().size());
        assertEquals("Bảo vệ 1", preview.blockers().get(0).shifts().get(0).guardName());
    }

    @Test
    @DisplayName("P1b (BR-GB-06): Đổi tòa nhà khi còn blocker -> ném IllegalStateException")
    void testUpdateTeam_ChangeBuildingBlocked() {
        UUID teamId = activeTeam.getId();
        UUID oldBuildingId = UUID.randomUUID();
        Building oldBuilding = Building.builder().id(oldBuildingId).name("Tòa Alpha").build();
        activeTeam.setBuilding(oldBuilding);

        UUID targetBuildingId = UUID.randomUUID();
        Building targetBuilding = Building.builder().id(targetBuildingId).name("Tòa Beta").build();

        GuardTeamCreateRequest updateReq = GuardTeamCreateRequest.builder()
                .teamName("Đội 1")
                .buildingId(targetBuildingId)
                .build();

        User member = User.builder().id(UUID.randomUUID()).fullName("Bảo vệ 1").userCode("SEC-001").build();
        GuardShift scheduledShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(member)
                .shiftDate(LocalDate.now().plusDays(2))
                .status(ShiftStatus.SCHEDULED)
                .building(oldBuilding)
                .build();

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(activeTeam));
        when(buildingRepository.findById(targetBuildingId)).thenReturn(Optional.of(targetBuilding));
        when(shiftRepository.findFutureScheduledShiftsInBuilding(eq(teamId), any(LocalDate.class), eq(oldBuildingId)))
                .thenReturn(List.of(scheduledShift));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardTeamService.updateTeam(teamId, updateReq));
        assertTrue(ex.getMessage().contains("Không thể đổi tòa nhà: còn 1 ca trực tương lai"));
    }

    @Test
    @DisplayName("P1b: Sửa đội không gửi buildingId -> giữ nguyên tòa nhà hiện tại")
    void testUpdateTeam_KeepExistingBuildingWhenNull() {
        UUID teamId = activeTeam.getId();
        UUID oldBuildingId = UUID.randomUUID();
        Building oldBuilding = Building.builder().id(oldBuildingId).name("Tòa Alpha").build();
        activeTeam.setBuilding(oldBuilding);

        GuardTeamCreateRequest updateReq = GuardTeamCreateRequest.builder()
                .teamName("Đội 1 mới")
                .buildingId(null) // Không gửi buildingId
                .build();

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(activeTeam));
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardTeamDto dto = guardTeamService.updateTeam(teamId, updateReq);

        assertNotNull(dto);
        assertEquals("Đội 1 mới", dto.getTeamName());
        assertEquals(oldBuildingId, dto.getBuildingId());
        assertEquals("Tòa Alpha", dto.getBuildingName());
    }
}
