package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaLevelPresetServiceTest {

    @Mock
    private AreaLevelPresetRepository presetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccessControlAuditService auditService;

    @InjectMocks
    private AreaLevelPresetService presetService;

    private User fm;
    private AreaLevelPreset publicPreset;
    private AreaLevelPreset restrictedPreset;

    @BeforeEach
    void setUp() {
        fm = User.builder()
                .id(UUID.randomUUID())
                .userCode("FM-001")
                .fullName("Quản Lý Cơ Sở")
                .email("fm@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .build();

        publicPreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .version(0L)
                .updatedBy(fm)
                .updatedAt(OffsetDateTime.now())
                .build();

        restrictedPreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .version(0L)
                .updatedBy(fm)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Lấy danh sách presets trả về đầy đủ các bản ghi cấu hình")
    void getAllPresets_ReturnsListOfPresets() {
        when(presetRepository.findAll()).thenReturn(List.of(publicPreset, restrictedPreset));

        List<LevelPresetResponse> result = presetService.getAllPresets();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(AreaLevel.PUBLIC, result.get(0).areaLevel());
        assertEquals(1, result.get(0).areaAccessLevel());
        assertFalse(result.get(0).explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("Cập nhật preset thành công -> ghi nhận audit log và trả về DTO mới")
    void updatePreset_Valid_Success() {
        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));
        when(presetRepository.save(any(AreaLevelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Tăng cường bảo vệ sảnh", 0L);
        LevelPresetResponse resp = presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn");

        assertNotNull(resp);
        assertEquals(2, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());

        verify(presetRepository).save(any(AreaLevelPreset.class));
        verify(auditService).record(any(), any(), eq("PUBLIC"), eq(null), eq(null), any(), any(), eq("Tăng cường bảo vệ sảnh"), eq(fm));
    }

    @Test
    @DisplayName("E.1: Cập nhật preset ghi nhận audit log đầy đủ các trường qua ArgumentCaptor (targetType, action, targetId, snapshots, reason, changedBy)")
    void updatePreset_AuditsChange_WithArgumentCaptor() {
        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));
        when(presetRepository.save(any(AreaLevelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Tăng cường bảo vệ sảnh", 0L);
        presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn");

        org.mockito.ArgumentCaptor<com.fa26se040.icss.enums.AccessControlTargetType> targetTypeCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.enums.AccessControlTargetType.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.enums.AccessControlAction> actionCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.enums.AccessControlAction.class);
        org.mockito.ArgumentCaptor<String> targetIdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Area> areaCaptor = org.mockito.ArgumentCaptor.forClass(Area.class);
        org.mockito.ArgumentCaptor<User> userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.dto.accesscontrol.snapshot.AccessControlAuditSnapshot> oldSnapshotCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.dto.accesscontrol.snapshot.AccessControlAuditSnapshot.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.dto.accesscontrol.snapshot.AccessControlAuditSnapshot> newSnapshotCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.dto.accesscontrol.snapshot.AccessControlAuditSnapshot.class);
        org.mockito.ArgumentCaptor<String> reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<User> actorCaptor = org.mockito.ArgumentCaptor.forClass(User.class);

        verify(auditService, org.mockito.Mockito.times(1)).record(
                targetTypeCaptor.capture(),
                actionCaptor.capture(),
                targetIdCaptor.capture(),
                areaCaptor.capture(),
                userCaptor.capture(),
                oldSnapshotCaptor.capture(),
                newSnapshotCaptor.capture(),
                reasonCaptor.capture(),
                actorCaptor.capture()
        );

        assertEquals(com.fa26se040.icss.enums.AccessControlTargetType.LEVEL_PRESET, targetTypeCaptor.getValue());
        assertEquals(com.fa26se040.icss.enums.AccessControlAction.UPDATE, actionCaptor.getValue());
        assertEquals("PUBLIC", targetIdCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertNull(areaCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertNull(userCaptor.getValue());
        assertEquals(new com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot(1, false), oldSnapshotCaptor.getValue());
        assertEquals(new com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot(2, true), newSnapshotCaptor.getValue());
        assertEquals("Tăng cường bảo vệ sảnh", reasonCaptor.getValue());
        assertEquals(fm, actorCaptor.getValue());
    }

    @Test
    @DisplayName("Cập nhật preset không tìm thấy loại khu vực -> ném AccessControlException(ERR_AC_004)")
    void updatePreset_NotFound_ThrowsNotFound() {
        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.empty());

        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, false, "Thay đổi", 0L);
        AccessControlException ex = assertThrows(AccessControlException.class,
                () -> presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn"));
        assertEquals(AccessControlErrorCode.ERR_AC_004, ex.getErrorCode());
    }

    @Test
    @DisplayName("Cập nhật preset với version không khớp (BR-PR-03) -> ném AccessControlException(ERR_AC_003)")
    void updatePreset_VersionMismatch_ThrowsConflict() {
        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset)); // version is 0L

        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Xung đột phiên bản", 1L); // passed version 1L != 0L
        AccessControlException ex = assertThrows(AccessControlException.class,
                () -> presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn"));

        assertEquals(AccessControlErrorCode.ERR_AC_003, ex.getErrorCode());
        verify(presetRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Cập nhật preset No-op (giá trị không đổi) -> không lưu DB, không ghi audit log")
    void updatePreset_NoOp_DoesNotSaveOrAudit() {
        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));

        // req has same values as publicPreset (level 1, false)
        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(1, false, "Không đổi", 0L);
        LevelPresetResponse resp = presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn");

        assertNotNull(resp);
        assertEquals(1, resp.areaAccessLevel());
        assertFalse(resp.explicitAuthorizationRequired());

        verify(presetRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BR-PR-02: Cập nhật preset không làm thay đổi các khu vực hiện hữu")
    void updatePreset_DoesNotAffectExistingAreas() {
        Area existingArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Sảnh chính")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .build();

        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fm));
        when(presetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));
        when(presetRepository.save(any(AreaLevelPreset.class))).thenAnswer(inv -> inv.getArgument(0));

        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Tăng cấp mặc định", 0L);
        presetService.updatePreset(AreaLevel.PUBLIC, req, "fm@fpt.edu.vn");

        // Existing area values remain unchanged
        assertEquals(1, existingArea.getAreaAccessLevel());
        assertFalse(existingArea.getExplicitAuthorizationRequired());
    }
}
