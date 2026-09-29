package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.AuditModuleRoleRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuditModuleRoleRepository auditModuleRoleRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private AuditService auditService;

    private User actor;
    private User subjectUser;
    private Area area;

    @BeforeEach
    void setUp() {
        actor = User.builder()
                .id(UUID.randomUUID())
                .userCode("FM-001")
                .fullName("Quản Lý Cơ Sở")
                .email("fm@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .build();

        subjectUser = User.builder()
                .id(UUID.randomUUID())
                .userCode("STU-001")
                .fullName("Nguyễn Sinh Viên")
                .email("student@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .build();

        area = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Lab Máy Tính")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .build();

        lenient().when(auditLogRepository.saveAndFlush(any(AuditLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật cấp độ người dùng (USER_ACCESS_LEVEL / UPDATE)")
    void record_UserAccessLevelUpdate_Success() {
        UserAccessLevelAuditSnapshot oldVal = new UserAccessLevelAuditSnapshot(1);
        UserAccessLevelAuditSnapshot newVal = new UserAccessLevelAuditSnapshot(2);

        auditService.record(
                AuditTargetType.USER_ACCESS_LEVEL,
                AuditAction.UPDATE,
                subjectUser.getId().toString(),
                (Area) null,
                subjectUser,
                oldVal,
                newVal,
                "Nâng cấp độ cho sinh viên NCKH",
                actor
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(AuditTargetType.USER_ACCESS_LEVEL, saved.getTargetType());
        assertEquals(AuditAction.UPDATE, saved.getAction());
        assertEquals(subjectUser.getId().toString(), saved.getTargetId());
        assertNull(saved.getArea());
        assertEquals(subjectUser, saved.getSubjectUser());
        assertEquals(actor, saved.getChangedBy());
        assertEquals("USER", saved.getActorType());
        assertNull(saved.getActorSource());
        assertNotNull(saved.getCorrelationId());
        assertEquals("Nâng cấp độ cho sinh viên NCKH", saved.getReason());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật quy tắc khu vực (AREA_ACCESS_RULES / UPDATE)")
    void record_AreaAccessRulesUpdate_Success() {
        AreaAccessRulesAuditSnapshot oldVal = new AreaAccessRulesAuditSnapshot(1, false);
        AreaAccessRulesAuditSnapshot newVal = new AreaAccessRulesAuditSnapshot(2, true);

        auditService.record(
                AuditTargetType.AREA_ACCESS_RULES,
                AuditAction.UPDATE,
                area.getId().toString(),
                area,
                null,
                oldVal,
                newVal,
                "Tăng cường kiểm soát phòng Lab",
                actor
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(AuditTargetType.AREA_ACCESS_RULES, saved.getTargetType());
        assertEquals(area, saved.getArea());
        assertNull(saved.getSubjectUser());
        assertEquals("Tăng cường kiểm soát phòng Lab", saved.getReason());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác gán nhân sự (AREA_ASSIGNMENT / ASSIGN)")
    void record_AreaAssignment_Success() {
        OffsetDateTime from = OffsetDateTime.now();
        OffsetDateTime to = from.plusDays(7);
        AreaAssignmentAuditSnapshot newVal = new AreaAssignmentAuditSnapshot(from, to, AssignedPersonnelStatus.ACTIVE);

        auditService.record(
                AuditTargetType.AREA_ASSIGNMENT,
                AuditAction.ASSIGN,
                UUID.randomUUID().toString(),
                area,
                subjectUser,
                null,
                newVal,
                "Gán trực Lab 1 tuần",
                actor
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(AuditTargetType.AREA_ASSIGNMENT, saved.getTargetType());
        assertEquals(AuditAction.ASSIGN, saved.getAction());
        assertNull(saved.getOldValue());
        assertNotNull(saved.getNewValue());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật cấu hình mặc định (LEVEL_PRESET / UPDATE)")
    void record_LevelPresetUpdate_Success() {
        LevelPresetAuditSnapshot oldVal = new LevelPresetAuditSnapshot(1, false);
        LevelPresetAuditSnapshot newVal = new LevelPresetAuditSnapshot(1, true);

        auditService.record(
                AuditTargetType.LEVEL_PRESET,
                AuditAction.UPDATE,
                "PUBLIC",
                (Area) null,
                null,
                oldVal,
                newVal,
                "Yêu cầu cấp thẻ cho cả khu vực public",
                actor
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(AuditTargetType.LEVEL_PRESET, saved.getTargetType());
        assertEquals("PUBLIC", saved.getTargetId());
    }

    @Test
    @DisplayName("Ghi log thành công cho actor hệ thống SYSTEM")
    void record_SystemActor_Success() {
        AuditActor systemActor = AuditActor.system("EXPIRE_JOB");
        auditService.record(
                AuditTargetType.ACCESS_REQUEST,
                AuditAction.EXPIRE,
                "REQ-123",
                (Area) null,
                null,
                null,
                null,
                "Hết hạn tự động",
                systemActor
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals("SYSTEM", saved.getActorType());
        assertEquals("EXPIRE_JOB", saved.getActorSource());
        assertNull(saved.getChangedBy());
        assertNotNull(saved.getCorrelationId());
    }

    @Test
    @DisplayName("Tra cứu danh sách nhật ký phân trang và map sang DTO không chứa PII (không chứa email)")
    void getAuditLogs_ReturnsMappedDtoWithoutPii() {
        when(auditModuleRoleRepository.findModulesByRole("ADMIN"))
                .thenReturn(List.of("AREA", "ACCESS_CONTROL", "ACCESS_REQUEST", "SYSTEM"));

        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .targetType(AuditTargetType.USER_ACCESS_LEVEL)
                .targetId(subjectUser.getId().toString())
                .action(AuditAction.UPDATE)
                .area(area)
                .subjectUser(subjectUser)
                .changedBy(actor)
                .actorType("USER")
                .oldValue("{\"accessLevel\":1}")
                .newValue("{\"accessLevel\":2}")
                .reason("Cập nhật cấp độ")
                .correlationId(UUID.randomUUID())
                .changedAt(OffsetDateTime.now())
                .build();

        Page<AuditLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1);
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        Page<AuditLogResponse> result = auditService.getAuditLogs(
                null,
                null,
                AuditTargetType.USER_ACCESS_LEVEL,
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10),
                "ADMIN"
        );

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());

        AuditLogResponse dto = result.getContent().get(0);
        assertEquals(log.getId(), dto.id());
        assertEquals(AuditTargetType.USER_ACCESS_LEVEL, dto.targetType());
        assertEquals(area.getId(), dto.areaId());
        assertEquals("Phòng Lab Máy Tính", dto.areaName());
        assertEquals("Quản Lý Cơ Sở", dto.changedByName());
        assertEquals("FM-001", dto.changedByUserCode());
        assertEquals("Nguyễn Sinh Viên", dto.subjectUserName());
        assertEquals("STU-001", dto.subjectUserCode());
        // Verify absence of email in DTO
        assertFalse(dto.toString().contains("student@fpt.edu.vn"));
        assertFalse(dto.toString().contains("fm@fpt.edu.vn"));
    }

    @Test
    @DisplayName("Tra cứu danh sách nhật ký có lọc theo areaId và trả về DTO đủ areaId và areaName")
    void getAuditLogs_FilterByAreaId_Success() {
        when(auditModuleRoleRepository.findModulesByRole("ADMIN"))
                .thenReturn(List.of("AREA", "ACCESS_CONTROL", "ACCESS_REQUEST", "SYSTEM"));

        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .targetType(AuditTargetType.AREA_ACCESS_RULES)
                .targetId(area.getId().toString())
                .action(AuditAction.UPDATE)
                .area(area)
                .changedBy(actor)
                .actorType("USER")
                .oldValue("{\"areaAccessLevel\":1,\"explicitAuthorizationRequired\":false}")
                .newValue("{\"areaAccessLevel\":2,\"explicitAuthorizationRequired\":true}")
                .reason("Cập nhật mức bảo vệ")
                .correlationId(UUID.randomUUID())
                .changedAt(OffsetDateTime.now())
                .build();

        Page<AuditLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1);
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        Page<AuditLogResponse> result = auditService.getAuditLogs(
                null,
                null,
                null,
                area.getId(),
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10),
                "ADMIN"
        );

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());

        AuditLogResponse dto = result.getContent().get(0);
        assertEquals(log.getId(), dto.id());
        assertEquals(area.getId(), dto.areaId());
        assertEquals("Phòng Lab Máy Tính", dto.areaName());
    }
}
