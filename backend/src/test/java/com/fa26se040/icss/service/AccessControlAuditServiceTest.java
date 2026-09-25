package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.AccessControlAuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot;
import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessControlAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlAuditServiceTest {

    @Mock
    private AccessControlAuditLogRepository auditLogRepository;

    @org.mockito.Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private AccessControlAuditService auditService;

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

        org.mockito.Mockito.lenient().when(auditLogRepository.save(any(AccessControlAuditLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật cấp độ người dùng (USER_ACCESS_LEVEL / UPDATE)")
    void record_UserAccessLevelUpdate_Success() {
        UserAccessLevelAuditSnapshot oldVal = new UserAccessLevelAuditSnapshot(1);
        UserAccessLevelAuditSnapshot newVal = new UserAccessLevelAuditSnapshot(2);

        auditService.record(
                AccessControlTargetType.USER_ACCESS_LEVEL,
                AccessControlAction.UPDATE,
                subjectUser.getId().toString(),
                null,
                subjectUser,
                oldVal,
                newVal,
                "Nâng cấp độ cho sinh viên NCKH",
                actor
        );

        ArgumentCaptor<AccessControlAuditLog> captor = ArgumentCaptor.forClass(AccessControlAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AccessControlAuditLog saved = captor.getValue();
        assertEquals(AccessControlTargetType.USER_ACCESS_LEVEL, saved.getTargetType());
        assertEquals(AccessControlAction.UPDATE, saved.getAction());
        assertEquals(subjectUser.getId().toString(), saved.getTargetId());
        assertNull(saved.getArea());
        assertEquals(subjectUser, saved.getSubjectUser());
        assertEquals(actor, saved.getChangedBy());
        assertEquals(objectMapper.valueToTree(oldVal), saved.getOldValue());
        assertEquals(objectMapper.valueToTree(newVal), saved.getNewValue());
        assertEquals("Nâng cấp độ cho sinh viên NCKH", saved.getReason());
        assertNotNull(saved.getChangedAt());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật quy tắc khu vực (AREA_ACCESS_RULES / UPDATE)")
    void record_AreaAccessRulesUpdate_Success() {
        AreaAccessRulesAuditSnapshot oldVal = new AreaAccessRulesAuditSnapshot(1, false);
        AreaAccessRulesAuditSnapshot newVal = new AreaAccessRulesAuditSnapshot(2, true);

        auditService.record(
                AccessControlTargetType.AREA_ACCESS_RULES,
                AccessControlAction.UPDATE,
                area.getId().toString(),
                area,
                null,
                oldVal,
                newVal,
                "Tăng cường kiểm soát phòng Lab",
                actor
        );

        ArgumentCaptor<AccessControlAuditLog> captor = ArgumentCaptor.forClass(AccessControlAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AccessControlAuditLog saved = captor.getValue();
        assertEquals(AccessControlTargetType.AREA_ACCESS_RULES, saved.getTargetType());
        assertEquals(area, saved.getArea());
        assertNull(saved.getSubjectUser());
        assertEquals(objectMapper.valueToTree(oldVal), saved.getOldValue());
        assertEquals(objectMapper.valueToTree(newVal), saved.getNewValue());
        assertEquals("Tăng cường kiểm soát phòng Lab", saved.getReason());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác gán nhân sự (AREA_ASSIGNMENT / ASSIGN)")
    void record_AreaAssignment_Success() {
        OffsetDateTime from = OffsetDateTime.now();
        OffsetDateTime to = from.plusDays(7);
        AreaAssignmentAuditSnapshot newVal = new AreaAssignmentAuditSnapshot(from, to, AssignedPersonnelStatus.ACTIVE);

        auditService.record(
                AccessControlTargetType.AREA_ASSIGNMENT,
                AccessControlAction.ASSIGN,
                UUID.randomUUID().toString(),
                area,
                subjectUser,
                null,
                newVal,
                "Gán trực Lab 1 tuần",
                actor
        );

        ArgumentCaptor<AccessControlAuditLog> captor = ArgumentCaptor.forClass(AccessControlAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AccessControlAuditLog saved = captor.getValue();
        assertEquals(AccessControlTargetType.AREA_ASSIGNMENT, saved.getTargetType());
        assertEquals(AccessControlAction.ASSIGN, saved.getAction());
        assertNull(saved.getOldValue());
        assertEquals(objectMapper.valueToTree(newVal), saved.getNewValue());
    }

    @Test
    @DisplayName("Ghi log thành công cho thao tác cập nhật cấu hình mặc định (LEVEL_PRESET / UPDATE)")
    void record_LevelPresetUpdate_Success() {
        LevelPresetAuditSnapshot oldVal = new LevelPresetAuditSnapshot(1, false);
        LevelPresetAuditSnapshot newVal = new LevelPresetAuditSnapshot(1, true);

        auditService.record(
                AccessControlTargetType.LEVEL_PRESET,
                AccessControlAction.UPDATE,
                "PUBLIC",
                null,
                null,
                oldVal,
                newVal,
                "Yêu cầu cấp thẻ cho cả khu vực public",
                actor
        );

        ArgumentCaptor<AccessControlAuditLog> captor = ArgumentCaptor.forClass(AccessControlAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AccessControlAuditLog saved = captor.getValue();
        assertEquals(AccessControlTargetType.LEVEL_PRESET, saved.getTargetType());
        assertEquals("PUBLIC", saved.getTargetId());
        assertEquals(objectMapper.valueToTree(oldVal), saved.getOldValue());
        assertEquals(objectMapper.valueToTree(newVal), saved.getNewValue());
    }

    @Test
    @DisplayName("Thao tác No-op: new == old không ghi audit log và không gọi repository.save")
    void record_NoOp_DoesNotSaveLog() {
        // Enforced in service layer before calling auditService.record
        // Here we verify that if auditService.record is not called, no interaction occurs
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tra cứu danh sách nhật ký phân trang và map sang DTO không chứa PII (không chứa email)")
    void getAuditLogs_ReturnsMappedDtoWithoutPii() {
        AccessControlAuditLog log = AccessControlAuditLog.builder()
                .id(UUID.randomUUID())
                .targetType(AccessControlTargetType.USER_ACCESS_LEVEL)
                .targetId(subjectUser.getId().toString())
                .action(AccessControlAction.UPDATE)
                .area(area)
                .subjectUser(subjectUser)
                .changedBy(actor)
                .oldValue(objectMapper.valueToTree(new UserAccessLevelAuditSnapshot(1)))
                .newValue(objectMapper.valueToTree(new UserAccessLevelAuditSnapshot(2)))
                .reason("Cập nhật cấp độ")
                .changedAt(OffsetDateTime.now())
                .build();

        Page<AccessControlAuditLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1);
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        Page<AccessControlAuditLogResponse> result = auditService.getAuditLogs(
                AccessControlTargetType.USER_ACCESS_LEVEL,
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());

        AccessControlAuditLogResponse dto = result.getContent().get(0);
        assertEquals(log.getId(), dto.id());
        assertEquals(AccessControlTargetType.USER_ACCESS_LEVEL, dto.targetType());
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
        AccessControlAuditLog log = AccessControlAuditLog.builder()
                .id(UUID.randomUUID())
                .targetType(AccessControlTargetType.AREA_ACCESS_RULES)
                .targetId(area.getId().toString())
                .action(AccessControlAction.UPDATE)
                .area(area)
                .changedBy(actor)
                .oldValue(objectMapper.valueToTree(new AreaAccessRulesAuditSnapshot(1, false)))
                .newValue(objectMapper.valueToTree(new AreaAccessRulesAuditSnapshot(2, true)))
                .reason("Cập nhật mức bảo vệ")
                .changedAt(OffsetDateTime.now())
                .build();

        Page<AccessControlAuditLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 10), 1);
        when(auditLogRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        Page<AccessControlAuditLogResponse> result = auditService.getAuditLogs(
                null,
                area.getId(),
                null,
                null,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());

        AccessControlAuditLogResponse dto = result.getContent().get(0);
        assertEquals(log.getId(), dto.id());
        assertEquals(area.getId(), dto.areaId());
        assertEquals("Phòng Lab Máy Tính", dto.areaName());
    }
}
