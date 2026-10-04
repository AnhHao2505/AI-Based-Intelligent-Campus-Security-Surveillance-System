package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaServiceAccessLevelTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaLevelPresetRepository areaLevelPresetRepository;

    @Mock
    private AreaValidator areaValidator;

    @Mock
    private AreaDependencyChecker dependencyChecker;

    @Mock
    private com.fa26se040.icss.service.AuditService auditService;

    @Mock
    private FloorRepository floorRepository;

    @InjectMocks
    private AreaService areaService;

    private User admin;
    private final String adminEmail = "admin@fpt.edu.vn";
    private User fm;
    private final String fmEmail = "fm@fpt.edu.vn";

    @BeforeEach
    void setUp() {
        admin = User.builder()
                .id(UUID.randomUUID())
                .userCode("ADMIN001")
                .fullName("Quản Trị Viên")
                .email(adminEmail)
                .role(Role.ADMIN)
                .isActive(true)
                .build();

        fm = User.builder()
                .id(UUID.randomUUID())
                .userCode("FM001")
                .fullName("Quản Lý Cơ Sở")
                .email(fmEmail)
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();

        Floor defaultFloor = Floor.builder()
                .id(UUID.randomUUID())
                .name("Tầng 1")
                .build();
        org.mockito.Mockito.lenient().when(floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase(any(), any()))
                .thenReturn(Optional.of(defaultFloor));
    }

    @Test
    @DisplayName("Tạo area luôn lấy access rules từ preset (PUBLIC: 1, false; PRIVATE: 3, true)")
    void createArea_AlwaysTakesAccessRulesFromPreset() {
        AreaCreateRequest reqPublic = AreaCreateRequest.builder()
                .name("Sảnh Chính")
                .areaLevel(AreaLevel.PUBLIC)
                .building("Tòa A")
                .floor("Tầng 1")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .build();

        when(areaValidator.validateAndNormalizeName(reqPublic.getName())).thenReturn(reqPublic.getName());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));

        AreaLevelPreset publicPreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .build();
        when(areaLevelPresetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));

        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse respPublic = areaService.create(reqPublic, adminEmail);
        assertNotNull(respPublic);
        assertEquals(1, respPublic.areaAccessLevel());
        assertFalse(respPublic.explicitAuthorizationRequired());

        // Test tạo khu vực HIGHLY_CONFIDENTIAL
        AreaCreateRequest reqPrivate = AreaCreateRequest.builder()
                .name("Phòng Máy Chủ")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .building("Tòa A")
                .floor("Tầng 2")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .build();
        when(areaValidator.validateAndNormalizeName(reqPrivate.getName())).thenReturn(reqPrivate.getName());

        AreaLevelPreset privatePreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .build();
        when(areaLevelPresetRepository.findById(AreaLevel.HIGHLY_CONFIDENTIAL)).thenReturn(Optional.of(privatePreset));

        AreaResponse respPrivate = areaService.create(reqPrivate, adminEmail);
        assertNotNull(respPrivate);
        assertEquals(3, respPrivate.areaAccessLevel());
        assertTrue(respPrivate.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("Tạo area khi thiếu preset -> fail-closed (accessLevel = 3, explicitAuthorizationRequired = true)")
    void createArea_WhenPresetMissing_FailsClosedWithLevel3AndExplicitAuthTrue() {
        AreaCreateRequest reqMissing = AreaCreateRequest.builder()
                .name("Phòng Mới")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .building("Tòa B")
                .floor("Tầng 1")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .build();

        when(areaValidator.validateAndNormalizeName(reqMissing.getName())).thenReturn(reqMissing.getName());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)).thenReturn(Optional.empty());

        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse resp = areaService.create(reqMissing, adminEmail);
        assertNotNull(resp);
        assertEquals(3, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("Đổi area_level khi update -> áp preset của loại mới; thiếu preset -> 3/true (BR-TC-04, Step 5b)")
    void updateArea_AreaLevelChange_AppliesTargetPresetFailClosed() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Học 101")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .building("Tòa A")
                .floor("Tầng 1")
                .isActive(true)
                .build();

        AreaUpdateRequest updateReq = AreaUpdateRequest.builder()
                .name("Phòng Học 101 Đổi Cấp")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .building("Tòa A")
                .floor("Tầng 1")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .reason("Đổi sang Tuyệt mật theo yêu cầu an ninh")
                .version(0L)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(areaValidator.validateAndNormalizeName(updateReq.getName())).thenReturn(updateReq.getName());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaResponse resp = areaService.update(areaId, updateReq, adminEmail);

        assertNotNull(resp);
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, resp.areaLevel());
        // Mock không có preset HIGHLY_CONFIDENTIAL -> fail-closed 3/true
        assertEquals(3, resp.areaAccessLevel(), "Đổi loại phải áp preset loại mới (thiếu preset -> 3)");
        assertTrue(resp.explicitAuthorizationRequired(), "Đổi loại phải áp preset loại mới (thiếu preset -> true)");
    }

    @Test
    @DisplayName("PATCH access-rules thành công -> cập nhật areaAccessLevel và cờ explicit")
    void updateAccessRules_Valid_Success() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Nghiên Cứu")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(fmEmail)).thenReturn(Optional.of(fm));
        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(1, true, "Cập nhật quyền vào phòng", 0L);
        AreaResponse resp = areaService.updateAccessRules(areaId, req, fmEmail);

        assertNotNull(resp);
        assertEquals(1, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("E.1: updateAccessRules ghi nhận audit log với actor là FACILITY_MANAGER qua ArgumentCaptor")
    void updateAccessRules_AuditsChange_WithArgumentCaptor() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Nghiên Cứu")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(fmEmail)).thenReturn(Optional.of(fm));
        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(1, true, "Cập nhật quyền vào phòng", 0L);
        areaService.updateAccessRules(areaId, req, fmEmail);

        org.mockito.ArgumentCaptor<com.fa26se040.icss.enums.AuditTargetType> targetTypeCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.enums.AuditTargetType.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.enums.AuditAction> actionCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.enums.AuditAction.class);
        org.mockito.ArgumentCaptor<String> targetIdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Area> areaCaptor = org.mockito.ArgumentCaptor.forClass(Area.class);
        org.mockito.ArgumentCaptor<User> userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot> oldSnapshotCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot.class);
        org.mockito.ArgumentCaptor<com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot> newSnapshotCaptor =
                org.mockito.ArgumentCaptor.forClass(com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot.class);
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

        assertEquals(com.fa26se040.icss.enums.AuditTargetType.AREA_ACCESS_RULES, targetTypeCaptor.getValue());
        assertEquals(com.fa26se040.icss.enums.AuditAction.UPDATE, actionCaptor.getValue());
        assertEquals(areaId.toString(), targetIdCaptor.getValue());
        assertEquals(existing, areaCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertNull(userCaptor.getValue());
        assertEquals(new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot(2, false), oldSnapshotCaptor.getValue());
        assertEquals(new com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot(1, true), newSnapshotCaptor.getValue());
        assertEquals("Cập nhật quyền vào phòng", reasonCaptor.getValue());
        assertEquals(fm, actorCaptor.getValue());
    }

    @Test
    @DisplayName("BR-AL-06: updateAccessRules no-op (level và explicit không đổi) -> không lưu DB, không ghi audit log")
    void updateAccessRules_NoOp_DoesNotSaveOrAudit() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Nghiên Cứu")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(2, true, "Không đổi gì cả", 0L);
        AreaResponse resp = areaService.updateAccessRules(areaId, req, fmEmail);

        assertNotNull(resp);
        assertEquals(2, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());

        verify(areaRepository, org.mockito.Mockito.never()).save(any(Area.class));
        verify(auditService, org.mockito.Mockito.never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(User.class));
    }

    @Test
    @DisplayName("PATCH access-rules cho area inactive hoặc đã xóa -> báo lỗi ERR_AREA_017")
    void updateAccessRules_InactiveOrDeletedArea_ThrowsAreaException() {
        UUID areaId = UUID.randomUUID();
        Area inactiveArea = Area.builder()
                .id(areaId)
                .name("Phòng Cũ")
                .isActive(false)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(inactiveArea));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(2, false, "Cập nhật", 0L);
        AreaException ex = assertThrows(AreaException.class, () -> areaService.updateAccessRules(areaId, req, adminEmail));
        assertEquals(AreaErrorCode.ERR_AREA_017, ex.getErrorCode());

        // Test deleted area
        Area deletedArea = Area.builder()
                .id(areaId)
                .name("Phòng Đã Xóa")
                .isActive(true)
                .deletedAt(OffsetDateTime.now())
                .build();
        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(deletedArea));
        AreaException exDel = assertThrows(AreaException.class, () -> areaService.updateAccessRules(areaId, req, adminEmail));
        assertEquals(AreaErrorCode.ERR_AREA_017, exDel.getErrorCode());
    }

    @Test
    @DisplayName("getAvailableAreasForRequest: Loại bỏ khu vực có areaAccessLevel == null (fail-closed)")
    void getAvailableAreasForRequest_WhenAreaAccessLevelNull_ExcludesArea() {
        User caller = User.builder()
                .id(UUID.randomUUID())
                .email("user@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(true)
                .build();
        when(userRepository.findByEmail("user@fpt.edu.vn")).thenReturn(Optional.of(caller));

        Area validArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Khu vực hợp lệ")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();

        Area nullLevelArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Khu vực cấp null")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(null)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();

        when(areaRepository.findAvailableForRequest()).thenReturn(java.util.List.of(validArea, nullLevelArea));

        var result = areaService.getAvailableAreasForRequest("user@fpt.edu.vn");

        assertEquals(1, result.size());
        assertEquals(validArea.getId(), result.get(0).id());
    }
}
