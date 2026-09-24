package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GuardTeamCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDto;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.repository.GuardTeamRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
